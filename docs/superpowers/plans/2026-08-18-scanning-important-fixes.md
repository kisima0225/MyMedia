# 扫描与任务租约 Important 修复实施计划

> ## ✅ 已执行并合入 `main`
>
> 提交 `a8bc946 fix: 修复扫描与任务租约重要问题`，17 个文件、+578/−38。
> 四个任务的产物已于 2026-08-19 逐项核对存在：
> `JobClaimService.renewLease` / `JobRepository.renewLease` / `JobScheduler` 心跳（Task 1）、
> `ScanOutcome.changedIds+reactivatedIds` / `ScannedFileChanged` / `LibraryContentBuilder.onFileChanged`（Task 2）、
> `scan/event/package-info.java` 的 `@NamedInterface("events")` 与 `docs/walkthrough/02-扫描框架.md`（Task 3）。
> 测试侧 `JobClaimServiceTest`（owner fencing）、`JobSchedulerTest.renewsLeaseWhileSlowHandlerRuns`、
> `ScanReconcilerTest` 与 `LibraryScanIntegrationTest` 的 reactivated 断言均在仓库里。
>
> **它改了跨计划的契约，读后续计划前先读这里**：
> `LibraryContentBuilder` 从两个回调变成三个（多了 `onFileChanged`），
> 跨模块事件契约成为 `scan :: events` 命名接口。计划 04 已按此对齐（见其 Self-Review），
> 计划 05 的 Task 6 Step 1/2 也已改写。
>
> 唯一无法事后核验的是 Task 4 Step 4 的 `final-fix-report.md`——它按计划写在当时的
> worktree `D:\MyMedia-2` 里且明确不纳入提交，那个 worktree 已经不在了。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复最终 whole-branch review 指出的扫描长事务、任务租约不续期、扫描事件缺少 named interface，以及领域层无法感知内容变化/重新激活四项 Important 问题。

**Architecture:** `LibraryScanner` 只负责非事务编排和最终事件发布；`ScanReconciler`、哈希持久化和 relocation 各自使用短数据库事务，文件遍历与哈希读取不占用长事务连接。`JobScheduler` 为每个异步 handler 建立 owner-fenced 心跳，所有完成回写继续由数据库 owner/租约条件保护；扫描变化事件作为 `scan.event` named interface 对外暴露。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · Spring Modulith 2.1.0 · PostgreSQL 17 · JUnit 5 · Testcontainers

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`，并实现 `.superpowers/sdd/2026-08-17-02-scanning` review package 中的四项 Important 要求。

## Global Constraints

- 保持 Java 25、Spring Boot 4.1、Flyway schema-only；本修复不新增迁移。
- `scan` 不依赖 `video` / `image`，不放宽 `allowedDependencies`。
- 实现类和 repository 保持 package-private；只有契约/API 和 public event records 对外公开。
- 文件扩展名与 `MediaTypeResolver` 继续使用白名单，不引入内容嗅探。
- 不删除 `MISSING` 记录；移动配对不得重复发布 discovered、vanished 或 changed。
- 不修改主 worktree `D:\MyMedia`，不提交日志、临时媒体或 `.superpowers` ledger/report。

---

### Task 1: Owner-fenced 租约续期

**Files:**
- Modify: `src/main/java/com/mymedia/jobs/JobRepository.java`
- Modify: `src/main/java/com/mymedia/jobs/JobClaimService.java`
- Modify: `src/main/java/com/mymedia/jobs/JobScheduler.java`
- Test: `src/test/java/com/mymedia/jobs/JobClaimServiceTest.java`
- Test: `src/test/java/com/mymedia/jobs/JobSchedulerTest.java`

**Interfaces:**
- Produces `JobClaimService.renewLease(Long jobId, String owner, Duration leaseDuration)`，返回是否更新到仍由该 owner 持有的 RUNNING 且未过期任务。
- Produces a package-private repository modifying query that returns update row count and checks id、RUNNING、owner、未过期租约。

- [x] **Step 1: 写 owner fencing 的失败集成测试**

在 `JobClaimServiceTest` 增加一条测试：worker A 以负租约抢占，回收并由 worker B 重新抢占，然后断言 A 的 `renewLease` 返回 `false`，任务仍由 B 持有；再增加一条正向测试，续期成功且新的 `leaseExpiresAt` 晚于旧值。

- [x] **Step 2: 运行 jobs focused 测试确认 RED**

Run: `mvn -B -ntp test "-Dtest=JobClaimServiceTest" -DfailIfNoTests=false`

Expected: 编译失败或找不到 `renewLease`，而不是测试代码错误。

- [x] **Step 3: 写慢 handler 续期的失败集成测试**

在 `JobSchedulerTest` 的测试属性中设置 `mymedia.jobs.lease-duration=PT0.3S`，增加测试：启动现有 blocking handler 后读取初始租约，等待 handler 仍为 RUNNING 且租约被延长，再释放 handler 并断言最终成功。保留现有 `slowHandlerDoesNotBlockOtherJobsOrTheNextPoll` 语义。

- [x] **Step 4: 实现 repository 和 claim service 续期**

增加如下语义的 native modifying query：

```sql
UPDATE job
SET lease_expires_at = :leaseExpiresAt
WHERE id = :id
  AND status = 'RUNNING'
  AND lease_owner = :owner
  AND lease_expires_at > :now
```

`JobClaimService.renewLease` 在一个事务中计算 `now` 与新过期时间，返回更新行数是否为 1，不加载或伪造跨事务实体状态。

- [x] **Step 5: 实现 scheduler heartbeat 生命周期**

新增单独的 `ScheduledExecutorService`。每个有 handler 的 RUNNING job 以约 `leaseDuration / 3` 的固定周期调用 `renewLease`；周期对零、负和纳秒级 duration 至少归一为可调度的正数。续租返回 false 或抛异常均记录 job、owner 和原因。handler 完成或失败在 `finally` 取消对应 `ScheduledFuture`；shutdown 等待现有任务后关闭 heartbeat executor，并保留现有异步 `pollOnce` 行为。

- [x] **Step 6: 运行 jobs focused 测试确认 GREEN**

Run: `mvn -B -ntp test "-Dtest=JobClaimServiceTest,JobSchedulerTest" -DfailIfNoTests=false`

Expected: 所有 jobs 测试通过，慢 handler 测试仍证明 poll 不阻塞。

### Task 2: 扫描事务边界与变化事件

**Files:**
- Modify: `src/main/java/com/mymedia/scan/ScannedFileRepository.java`
- Modify: `src/main/java/com/mymedia/scan/ScanOutcome.java`
- Modify: `src/main/java/com/mymedia/scan/ScanReconciler.java`
- Modify: `src/main/java/com/mymedia/scan/RelocationDetector.java`
- Modify: `src/main/java/com/mymedia/scan/LibraryScanner.java`
- Modify: `src/main/java/com/mymedia/scan/spi/LibraryContentBuilder.java`
- Create: `src/main/java/com/mymedia/scan/event/ScannedFileChanged.java`
- Test: `src/test/java/com/mymedia/scan/ScanReconcilerTest.java`
- Test: `src/test/java/com/mymedia/scan/LibraryScanIntegrationTest.java`

**Interfaces:**
- Produces `ScannedFileChanged(Long scannedFileId, Long libraryId, String relativePath, long sizeBytes, Instant mtime, boolean reactivated)`。
- Adds `LibraryContentBuilder.onFileChanged(ScannedFileChanged event)`。
- `ScanOutcome` records content-changed ids and reactivated ids without returning managed JPA entities across transaction boundaries。

- [x] **Step 1: 写 reconciler 的变化/恢复失败测试**

在 `ScanReconcilerTest` 增加断言：size 或 mtime 变化时 `changedIds` 包含稳定 id、持久化 size/mtime 更新；ACTIVE -> MISSING -> 同路径 ACTIVE 时 `reactivatedIds` 包含原 id 且 id 不变，未新增记录。

- [x] **Step 2: 运行 scan reconciler focused 测试确认 RED**

Run: `mvn -B -ntp test "-Dtest=ScanReconcilerTest" -DfailIfNoTests=false`

Expected: 因 `ScanOutcome` 尚无变化 id API 或断言不满足而失败。

- [x] **Step 3: 写真实扫描事件的失败集成测试**

在 `LibraryScanIntegrationTest` 扩展事件 recorder，并增加三类断言：

```java
assertThat(recorder.changed()).singleElement()
        .satisfies(event -> assertThat(event.reactivated()).isFalse());
assertThat(recorder.changed()).singleElement()
        .extracting(ScannedFileChanged::sizeBytes, ScannedFileChanged::mtime)
        .containsExactly(expectedSize, expectedMtime);
assertThat(recorder.changed()).singleElement()
        .extracting(ScannedFileChanged::reactivated)
        .isEqualTo(true);
```

测试同路径恢复必须只得到 changed/reactivated，不得到 discovered 或 relocated；移动测试必须保持 `relocated -> completed` 且没有重复 changed/discovered/vanished。事件 recorder 记录类型顺序，避免只验证最终集合。

- [x] **Step 4: 运行集成测试确认 RED**

Run: `mvn -B -ntp test "-Dtest=LibraryScanIntegrationTest" -DfailIfNoTests=false`

Expected: 因事件类型、回调或 scanner 发布逻辑不存在而编译失败。

- [x] **Step 5: 拆除 scanner 外层事务并修复跨事务状态**

删除 `LibraryScanner.scan` 的 `@Transactional`。保留 `ScanReconciler.reconcile` 和 relocation 数据库应用的短事务；哈希读取不在事务中进行，使用按 id 的 modifying update 持久化哈希。scanner 只跨事务传递 id、路径和不可变 outcome 数据；事件在 relocation 事务返回后、completion 之前发布。

- [x] **Step 6: 让 reconciler 记录 changed/reactivated ids**

对已有记录先捕获旧状态：内容 size/mtime 变化加入 changed ids；旧状态为 MISSING 且本轮命中路径加入 reactivated ids；MISSING 恢复即使内容未变也必须记录。新发现只走 discovered，不额外发布 changed。

- [x] **Step 7: 添加 changed event、SPI 回调和最终事件发布**

新增 public event record 携带最终 ACTIVE 记录的 id、library、path、size、mtime 和 `reactivated`；`LibraryContentBuilder` 增加对应回调。relocation detector 返回配对结果而不是在数据库事务内发布事件；scanner 按最终数据库状态发布 relocation、discovered、vanished、changed，最后发布 completed，并以 id 去重 changed/reactivated。

- [x] **Step 8: 运行 scan focused 测试确认 GREEN**

Run: `mvn -B -ntp test "-Dtest=ScanReconcilerTest,RelocationDetectorTest,LibraryScanIntegrationTest" -DfailIfNoTests=false`

Expected: 现有新增、消失、移动顺序和新 size/mtime、同路径恢复断言全部通过。

### Task 3: Modulith events named interface 与 walkthrough

**Files:**
- Create: `src/main/java/com/mymedia/scan/event/package-info.java`
- Modify: `docs/walkthrough/02-扫描框架.md`
- Test: `src/test/java/com/mymedia/ModularityTests.java`（仅在现有测试不足时补契约断言）

**Interfaces:**
- Produces `scan :: events` named interface，覆盖 `scan.event` 中所有 public event records。
- 保留 `scan :: spi`，不改变 `com.mymedia.scan` 的 allowed dependencies。

- [x] **Step 1: 写 named interface 契约测试或先运行现有 Modulith 测试**

先运行 `ModularityTests` 记录当前行为；若测试 API 能稳定检查 named interface，则增加对 `events` 的最小断言，否则以架构测试加载新增 package-info 为 RED/GREEN 证据。

- [x] **Step 2: 增加 `@NamedInterface("events")`**

创建 `scan/event/package-info.java`，只声明 `org.springframework.modulith.NamedInterface("events")`，不引入任何具体领域依赖。

- [x] **Step 3: 更新 walkthrough 契约说明**

说明未来 video/image 必须显式依赖 `scan :: spi` 与 `scan :: events`；事件 records 属于 events named interface，SPI 属于 spi named interface，scan 仍不依赖具体领域模块。

- [x] **Step 4: 运行 Modulith 测试确认 GREEN**

Run: `mvn -B -ntp test "-Dtest=ModularityTests" -DfailIfNoTests=false`

Expected: 2 tests pass and module dependency output仍只包含既有 allowed dependencies。

### Task 4: 最终验证、报告与提交

**Files:**
- Create: `D:\MyMedia-2\.superpowers\sdd\2026-08-17-02-scanning\final-fix-report.md`（不纳入 commit）

- [x] **Step 1: 运行 focused jobs/scan/Modulith 测试**

Run: `mvn -B -ntp test "-Dtest=JobClaimServiceTest,JobSchedulerTest,ScanReconcilerTest,RelocationDetectorTest,LibraryScanIntegrationTest,ModularityTests" -DfailIfNoTests=false`

- [x] **Step 2: 运行全量 verify**

Run: `mvn -B -ntp verify`

- [x] **Step 3: 自审 worktree diff**

检查 `git diff --check`、事务注解、事件顺序、owner fencing、未修改主 worktree，确认没有 migration、日志、临时媒体或 ledger 被纳入提交。

- [x] **Step 4: 写完整 final-fix-report.md**

逐项记录修复文件位置、RED/GREEN/focused/verify 命令和关键输出，明确列出未处理的 Minor：DirectoryWalker 不可读子树完整性标记、saveAndFlush 批处理、采样哈希长度测试强度、Windows symlink skip、malformed payload/404/额外事件顺序覆盖。

- [x] **Step 5: 提交单个中文 commit**

```text
git add src docs
git commit -m "fix: 修复扫描与任务租约重要问题"
```

不要 stage `final-fix-report.md`、`.superpowers` ledger、日志或临时媒体。
