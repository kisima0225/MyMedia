# Task 6 事件接线与派生资源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让新建媒体条目自动排预览、扫描完成后补齐缺失封面，并通过带授权和缓存的流式端点访问派生资源。

**Architecture:** `LibraryScanner.scan` 提供扫描事务边界；预览监听器使用同步 `@TransactionalEventListener(phase = AFTER_COMMIT)`，在领域数据提交后调用现有 `PreviewTrigger`。补齐监听器按媒体库 domain 批量扫描无封面对象并依赖 `dedup_key` 幂等。资源端点沿 `derived_asset -> scanned_file -> library` 查询归属和权限，未知或无权统一返回 404。

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring MVC `StreamingResponseBody`, Spring Security HTTP Basic, PostgreSQL/Flyway, JUnit 5, MockMvc, Awaitility。

**Spec:** `.superpowers/sdd/2026-08-17-05-preview-metadata/task-6-brief.md`

## Global Constraints

- 使用现有公开 user/library/scan/preview API，不暴露 `ImageCatalogService.openPageForProcessing`。
- `video` / `image` 不得依赖 `preview` / `metadata`；四个已存在的 `package-info.java` 命名接口只有在实际不等价时才修改。
- `@TransactionalEventListener` 必须在 domain transaction commit 后同步执行，不新增事件 outbox。
- 使用 JobPoller 的测试必须设置 `mymedia.jobs.enabled=true` 和 `mymedia.jobs.poll-interval=PT1H`。
- JobPoller 测试按目标 job 状态条件等待，不使用全局 job 数量；测试结束前不得留下指向已删除临时路径的 PENDING/RUNNING job。
- 实现类保持 package-private，不使用 Mockito。

---

### Task 1: 先建立接线与资源端点的失败测试

**Files:**
- Create: `src/test/java/com/mymedia/preview/PreviewWiringTest.java`
- Create: `src/test/java/com/mymedia/preview/AssetControllerTest.java`

**Interfaces:**
- Consumes: `LibraryService`, `ScanTrigger`, `JobPoller`, `JobQueue`, `VideoCatalogService`, `DerivedAssetService`, `LibraryAccessService`, `UserRegistrationService`, `MockMvc`。
- Produces: 明确验证扫描后自动排 `PREVIEW_GENERATE`、任务完成后写入封面，以及派生资源的 200/401/404/304 契约。

- [ ] **Step 1: Write the focused tests**

  测试类继承 `AbstractIntegrationTest`，声明：

  ```java
  @TestPropertySource(properties = {
          "mymedia.jobs.enabled=true",
          "mymedia.jobs.poll-interval=PT1H",
          "mymedia.preview.root=target/test-derived"
  })
  ```

  用 `@Import` 注入现有 `StubCommandRunner`。扫描后通过 `JobQueue.findById(scanJobId)` 或按目标 payload 查询具体预览任务，并用 Awaitility 反复 `jobPoller.pollOnce()` 直到目标 job 进入 `SUCCEEDED` 或 `FAILED`；不得以全局队列数量作为等待条件。验证三条接线场景：首次扫描自动排预览、目标预览完成后写封面、删除 `derived_asset` 后再次扫描重新生成封面。资源测试创建访问用户和陌生用户，验证授权用户得到 JPEG 字节、陌生用户得到 404、匿名请求得到 401、匹配 ETag 得到 304、未知 id 得到 404；对于 `StreamingResponseBody` 使用 `asyncDispatch` 等待实际字节输出。

- [ ] **Step 2: Run focused tests to record RED**

  Run from `D:\MyMedia-5`:

  ```powershell
  mvn -B -ntp test "-Dtest=PreviewWiringTest,AssetControllerTest" "-DfailIfNoTests=false"
  ```

  Expected: tests fail because no preview event/backfill listeners exist and `/api/assets/{id}` has no controller. Record the exact failure summary in `task-6-report.md` after implementation.

---

### Task 2: Implement event wiring and scan backfill

**Files:**
- Modify: `src/main/java/com/mymedia/scan/LibraryScanner.java`
- Create: `src/main/java/com/mymedia/preview/PreviewEventListener.java`
- Create: `src/main/java/com/mymedia/preview/PreviewBackfill.java`
- Modify: `src/main/java/com/mymedia/preview/package-info.java`

**Interfaces:**
- Consumes: `VideoItemCreated`, `ImageNodeCreated`, `LibraryScanCompleted`, `VideoCatalogService`, `ImageCatalogService`, `LibraryService`, `PreviewTrigger`。
- Produces: package-private Spring beans whose post-commit callbacks enqueue idempotent preview jobs。

- [ ] **Step 1: Add the scanner transaction boundary**

  Annotate `LibraryScanner.scan(Long libraryId)` with `@Transactional`. Keep the existing event order and scanner behavior unchanged; this makes reconciliation, content builders, finalizers, and `LibraryScanCompleted` part of one committed domain transaction so AFTER_COMMIT consumers observe committed rows.

- [ ] **Step 2: Add `PreviewEventListener`**

  Add package-private `@Component` with constructor dependencies `VideoCatalogService` and `PreviewTrigger`. Add two methods annotated with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`: expand `VideoItemCreated.itemId()` through `filesOf` and call `requestVideoPreview(file.getId())`; call `requestImagePreview(event.nodeId())` for `ImageNodeCreated`. Do not add asynchronous execution or an outbox.

- [ ] **Step 3: Add `PreviewBackfill`**

  Add package-private `@Component` with `LibraryService`, `VideoCatalogService`, `ImageCatalogService`, and `PreviewTrigger`. Handle `LibraryScanCompleted` with synchronous `AFTER_COMMIT`, resolve the library domain, select at most 500 ids from `itemsWithoutCover` or `nodesWithoutCover`, and enqueue the matching preview jobs. Keep the existing public query APIs and rely on `PreviewTrigger` deduplication.

- [ ] **Step 4: Tighten the preview module declaration**

  Preserve the already-correct scan/video/image package declarations. Change only `preview/package-info.java` to allow `scan::events`, `video::events`, `image::events`; add `user` because `AssetController` directly consumes `UserQueryService`, which is a real direct module dependency. Do not add `jobs` to `video` because it does not use that module.

- [ ] **Step 5: Run wiring tests to verify GREEN**

  ```powershell
  mvn -B -ntp test "-Dtest=PreviewWiringTest" "-DfailIfNoTests=false"
  ```

  Expected: all wiring tests pass and all jobs whose payload targets the test files reach a terminal state before the test fixture path is removed.

---

### Task 3: Implement the secured derived-asset endpoint

**Files:**
- Create: `src/main/java/com/mymedia/preview/web/AssetController.java`
- Modify: `src/test/java/com/mymedia/preview/AssetControllerTest.java` only if async handling needs the focused contract test to complete reliably。

**Interfaces:**
- Consumes: `DerivedAssetService`, `ScannedFileQueryService`, `LibraryAccessService`, `UserQueryService`, `NotFoundException`。
- Produces: package-private `GET /api/assets/{id}` returning `ResponseEntity<StreamingResponseBody>`。

- [ ] **Step 1: Implement lookup and access control**

  Load the derived asset by id, resolve its `sourceScannedFileId` through `ScannedFileQueryService`, resolve the authenticated username through `UserQueryService`, and call `LibraryAccessService.canAccess`. Throw `NotFoundException` for missing assets, missing source files, unknown users, or inaccessible libraries so strangers receive 404 rather than 403.

- [ ] **Step 2: Implement cache and streaming behavior**

  Build the strong ETag from asset id and `generatedAt.toEpochMilli()`. If it exactly matches `If-None-Match`, return 304 with ETag and `private, max-age=604800`. Otherwise verify the derived path is readable, return 200 with ETag, cache control, `contentType()`, and `contentLength(sizeBytes)`, and stream using `Files.newInputStream` plus `InputStream.transferTo`. Do not call `openPageForProcessing`.

- [ ] **Step 3: Run the endpoint tests**

  ```powershell
  mvn -B -ntp test "-Dtest=AssetControllerTest" "-DfailIfNoTests=false"
  ```

  Expected: all endpoint contract tests pass, including the async body read and 304 response without a body.

---

### Task 4: Regression verification, self-review, report, and commit

**Files:**
- Create: `.superpowers/sdd/2026-08-17-05-preview-metadata/task-6-report.md`

- [ ] **Step 1: Run focused Task 6 and architecture/preview regressions**

  ```powershell
  mvn -B -ntp test "-Dtest=PreviewWiringTest,AssetControllerTest,ModularityTests,VideoPreviewJobTest,ImagePreviewJobTest,SpriteJobTest" "-DfailIfNoTests=false"
  ```

- [ ] **Step 2: Run one full Maven test suite**

  ```powershell
  mvn -B -ntp test
  ```

- [ ] **Step 3: Self-review the diff**

  Check module direction, explicit AFTER_COMMIT semantics, direct dependency declarations, 404 information hiding, ETag/streaming behavior, no leaked temp-path jobs, package-private implementation classes, and absence of unrelated edits. Inspect `git status`, `git diff --stat`, and `git diff`.

- [ ] **Step 4: Write the detailed report**

  Record changed files, the RED command and failure summary, GREEN focused/regression/full-suite commands and exact test output, self-review result, and any residual concerns in `.superpowers/sdd/2026-08-17-05-preview-metadata/task-6-report.md`.

- [ ] **Step 5: Commit only intended files**

  ```powershell
  git add src/main/java/com/mymedia src/test/java/com/mymedia/preview docs/superpowers/plans/2026-08-22-task-6-event-wiring-derived-assets.md .superpowers/sdd/2026-08-17-05-preview-metadata/task-6-report.md
  git commit -m "feat: 接线预览事件、扫描补齐与派生资源端点"
  ```
