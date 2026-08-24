# MyMedia 实施计划 05：预览生成与元数据刮削

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个条目在扫描结束的瞬间就有封面、缩略图与可用元数据：`preview` 模块用 ffprobe/ffmpeg 与 ImageIO 生成派生资源（封面、缩略图、进度条雪碧图 + WebVTT），`metadata` 模块用一条可插拔的提供者链（本地 NFO → 外部刮削器 → 文件名兜底）填充元数据，低置信度结果进候选队列交给用户确认。

**Architecture:** 两个新模块**单向依赖**领域模块：`preview` / `metadata` 订阅 `VideoItemCreated` / `ImageNodeCreated` 事件，并直接调用 `video` / `image` 的公开写回 API；领域模块永远不反向引用它们，由 `ModularityTests` 强制。**这里刻意不像 `scan` 那样做 SPI 倒置**——理由见 Global Constraints 与 Task 12 的 ADR-004。所有外部进程调用经过可注入的 `CommandRunner`，因此解析逻辑全是纯单元测试，任务处理器的集成测试用桩 runner，不要求本机装 ffmpeg。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · PostgreSQL 17 · ffmpeg / ffprobe（烘焙进镜像）· `javax.imageio`（JDK 自带）· `RestClient` · `ConcurrentMapCacheManager`

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`（覆盖 §4.3 领域事件、§6.2 `derived_asset`、§6.6 刮削候选、§7.2 元数据获取、路线图 P8–P9）

**前置计划:** 01 基础设施（**已执行完毕**）、02 扫描框架、03 视频域、04 图片域 必须全部完成且 `mvn verify` 通过。

---

## Global Constraints

**继承计划 01、02、03、04 的全部 Global Constraints。执行前必须先读一遍计划 01 的该章节。**

本计划新增：

### 迁移编号：V10、V11

本计划占用 `V10__derived_asset.sql` 与 `V11__scrape_candidate.sql`。计划 01 已用掉 V1–V4（含补丁迁移 `V1_1__event_publication.sql`），计划 02 用 V5，计划 03 用 V6–V7，计划 04 用 V8–V9。**不要占用别的编号。**

### 依赖方向：`preview` / `metadata` 单向依赖领域模块

```
preview  → shared, library, jobs, scan, video, image
metadata → shared, library, jobs, scan, video, image
video / image → 绝不引用 preview / metadata
```

**这条约束由 `ModularityTests` 强制**，强制点是各模块 `package-info.java` 上的显式 `allowedDependencies`。
`video` 那份已在 `main` 上（2026-08-19 补），`image` 那份由计划 04 Task 1 写全；
本计划 Task 6 只负责 `preview` / `metadata` 自己的两份，并核对上游没走样。

**为什么这里不做 SPI 倒置**（ADR-004 的素材，写代码时心里要有这个答案）：

- `scan` 之所以倒置（定义 `LibraryContentBuilder` 让领域模块实现），是因为**物理扫描真正与领域无关**——加入第三个域（音频）不需要改扫描一行代码，倒置买到的是实打实的开闭。
- **刮削本身就是领域特定的**：TMDB 管电影、Bangumi 管番剧与漫画，NFO 的字段名两个域也不一样。把它倒置只会把 `if (domain == VIDEO)` 换个地方摆，还多一层间接。
- 同一个项目里两次相反的取舍、各有各的理由，比"我全都倒置了"更能说明判断力。

### 前置修正：Modulith 命名接口

Spring Modulith 把模块的**嵌套包视为内部实现**，除非该包用 `@NamedInterface` 显式暴露。计划 02、03、04 都把公开类型放进了嵌套包（`scan.spi`、`scan.event`、`video.event`、`image.event`），但**没有写命名接口声明**——跨模块引用它们会让 `ApplicationModules.verify()` 失败。

本计划 Task 6 Step 1 负责补齐这四个 `package-info.java`。**若执行计划 02/03/04 时已经补过（内容等价），跳过该步即可。**

### ⚠ Boot 4.1 用的是 Jackson **3**，包名不是 `com.fasterxml`

本计划大量解析 JSON（ffprobe 输出、任务载荷、刮削响应）。**照 Jackson 2 的记忆写会全部编译失败。**

实测自本仓库已构建的 `target/mymedia-0.0.1-SNAPSHOT.jar`：

```
BOOT-INF/lib/jackson-databind-3.1.4.jar
BOOT-INF/lib/jackson-core-3.1.4.jar
BOOT-INF/lib/jackson-annotations-2.21.jar     ← 只有注解还在旧坐标
```

`javap` 确认的差异（全部实测，不是推测）：

| Jackson 2 写法（错误） | Jackson 3 正确写法 |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` | **`tools.jackson.databind.ObjectMapper`** |
| `com.fasterxml.jackson.databind.JsonNode` | **`tools.jackson.databind.JsonNode`** |
| `JsonProcessingException`（受检） | `tools.jackson.core.JacksonException` **继承 `RuntimeException`，不受检** |
| `node.asText()` / `asText(String)` | `node.asString()` / `asString(String)`（`asText` 仍在但已是旧名） |
| `node.fields()` | `node.properties()` → `Set<Map.Entry<String, JsonNode>>` |

`new ObjectMapper()`、`readTree(String)`、`readValue(String, Class)`、`writeValueAsString(Object)`、`nullNode()`、`JsonNode implements Iterable<JsonNode>` 均照旧可用。

因为异常改成了不受检，**捕获时一律写 `catch (Exception e)`**——写 `catch (JsonProcessingException e)` 既找不到类，写 `catch (JacksonException e)` 又会在某些位置被判为"不可能抛出"。

注解仍在 `com.fasterxml.jackson.annotation`（本计划用不到）。

### 不使用 Mockito

计划 01 把 Boot 4 的 test starter 拆开引入，**Mockito 是否在 classpath 上没有验证过**。所有替身一律手写：

- 纯单元测试 → 手写实现类或 lambda。
- 集成测试需要替换 bean → `@TestConfiguration` + `@Primary` 提供桩实现（Task 3 有完整范例）。

### 外部进程一律经过 `CommandRunner`

`ffmpeg` / `ffprobe` **烘焙进应用 Docker 镜像，不要求本机安装**。因此：

- 所有输出解析（ffprobe JSON → `video_file` 字段）是**纯单元测试**，喂 fixture 字符串。
- 命令行构造（参数顺序、`-ss` 的位置、fps 表达式）也是**纯单元测试**，断言 `List<String>` 逐项相等。
- 任务处理器的集成测试注入桩 `CommandRunner`，它按命令行末尾的输出路径写一张真实的小 JPEG（用 `ImageIO` 现场生成，因为下游缩略图确实要 `ImageIO.read` 它）。
- 真机调用只保留**一个**带 `assumeTrue(ffprobe 可用)` 的测试（Task 12），本机没装就跳过，不算失败。

### JSONB 与 TEXT[] 列一律不做 JPA 映射

沿用计划 03、04 的模式：`metadata` / `raw_metadata` / `field_sources` / `locked_fields` / `metadata_providers` / `probe_raw` 全部由 package-private 的 `JdbcTemplate` 类读写，**不要临时改成 `@JdbcTypeCode`**。原因：`ddl-auto=validate` 对 Hibernate 的数组/JSON 类型映射很挑，而这些列天然适合直读直写。

### 元数据优先级：两套既有机制，不引入第三套

spec §7.2 的 `用户编辑 > 本地元数据文件 > 刮削 > 文件名`，用两个机制表达，**不要再造一套 tier 比较**：

1. **链的顺序表达优先级**：`LocalNfo → 各刮削器（按 libraries.metadata_providers 的配置顺序） → Filename 兜底`，**命中即停**。
2. **`locked_fields` 表达覆盖保护**：用户编辑过的字段进 `locked_fields`，任何刮削不得覆盖。

`field_sources` 只记录"这个字段是谁写的"，供界面展示，**不参与判定**。

### 找不到不是错误

`NO_MATCH` 是正常状态：安静回落到 `FilenameProvider` 的结果，界面不显示为错误。只有**网络故障与限流**才置 `ERROR` 并进入重试退避。`libraries.metadata_providers` 为空数组的库，其条目直接 `NOT_APPLICABLE`，**根本不排 `METADATA_FETCH` 任务**。

### 派生资源与 spec §6.2 的承诺

「派生目录删光后可由任务队列全量重建，不碰任何用户数据」这句承诺，本计划用**一条外键**兑现：`video_item.cover_asset_id` / `image_node.cover_asset_id` → `derived_asset(id) ON DELETE SET NULL`。清空派生目录时同时 `DELETE FROM derived_asset`，所有封面引用被数据库自动置空，下一次扫描的补齐逻辑就会全量重建。**Task 1 有一个测试专门断言这个级联行为**——它是这句承诺的证据，不是可选项。

### 与 spec §4.3 的一处偏离（已定稿）

spec 说领域事件走 Spring Modulith 的事件发布注册表（`@ApplicationModuleListener`，异步 + 持久化 + 可补发）。本计划的监听器改用**同步的 `@TransactionalEventListener(AFTER_COMMIT)`**。

理由：本计划的事件监听器**只做一件事——排一个持久化任务**。真正的重试、租约、失败历史由 `job` 表负责，再叠一层事件补发就是两套持久化机制解决同一个问题。而 spec 担心的"扫描完了但缩略图没生成"，本计划用**第二张网**兜住：`LibraryScanCompleted` 上的补齐监听器会把所有还没有封面的条目重新排队——这张网同时兑现了"派生目录删光后可全量重建"。

附带收益：同步监听器让集成测试是确定性的，不需要 `awaitility` 轮询异步边界。

### 命名与常量

| 常量 | 值 | 定义位置 |
|---|---|---|
| 任务类型 | `PREVIEW_GENERATE` | `PreviewJobHandler.JOB_TYPE` |
| 任务类型 | `SPRITE_GENERATE` | `SpriteJobHandler.JOB_TYPE` |
| 任务类型 | `METADATA_FETCH` | `MetadataFetchJobHandler.JOB_TYPE` |
| 雪碧图帧数 | 100（固定） | `PreviewProperties.spriteFrames` |
| 雪碧图网格 | 10 × 10（固定，永远单张） | `PreviewProperties.spriteColumns` |
| 自动应用阈值 | 相似度 ≥ 0.8 | `MetadataProperties.autoApplyThreshold` |
| 待确认阈值 | 0.4 ≤ 相似度 < 0.8 | `MetadataProperties.reviewThreshold` |

---

## File Structure

```
src/main/java/com/mymedia/preview/
├── package-info.java                  @ApplicationModule("Preview") + allowedDependencies
├── DerivedAssetKind.java              public 枚举 COVER / THUMBNAIL / SPRITE_SHEET / SPRITE_VTT
├── DerivedAsset.java                  public 实体 → 表 derived_asset
├── DerivedAssetRepository.java        package-private
├── DerivedAssetStorage.java           package-private：两级分片路径、写入、根目录
├── DerivedAssetService.java           public API：登记（幂等 upsert）、查询、开流
├── PreviewProperties.java             package-private：@ConfigurationProperties
├── CommandResult.java                 public record（桩实现也要用）
├── CommandRunner.java                 public 接口：外部进程调用的唯一入口
├── ProcessCommandRunner.java          package-private 实现：ProcessBuilder + 超时
├── FfprobeOutput.java                 package-private record：探测结果
├── FfprobeParser.java                 package-private：JSON → FfprobeOutput（纯逻辑）
├── MediaCommands.java                 package-private：命令行构造（纯逻辑）
├── ImageScaler.java                   package-private：ImageIO 等比缩放（纯逻辑）
├── SourceFileLocator.java             package-private：物理文件 id → 磁盘绝对路径
├── PreviewTarget.java                 package-private 枚举 VIDEO_FILE / IMAGE_NODE
├── VideoPreviewGenerator.java         package-private：探测 + 抽帧 + 缩略图
├── ImagePreviewGenerator.java         package-private：图集/漫画首页封面
├── PreviewJobHandler.java             package-private：PREVIEW_GENERATE
├── WebVttWriter.java                  package-private：几何 → VTT 文本（纯逻辑）
├── SpriteJobHandler.java              package-private：SPRITE_GENERATE
├── PreviewTrigger.java                public API：排队入口
├── PreviewEventListener.java          package-private：订阅两个领域事件
├── PreviewBackfill.java               package-private：订阅 LibraryScanCompleted
└── web/
    └── AssetController.java           GET /api/assets/{id}（直接流字节，没有 DTO）

src/main/java/com/mymedia/metadata/
├── package-info.java                  @ApplicationModule("Metadata") + allowedDependencies
├── MetadataProvider.java              public SPI
├── ScrapeSubject.java                 public record：待刮削对象
├── MetadataCandidate.java             public record：候选 + 置信度
├── ProviderUnavailableException.java  public：网络/限流故障（→ ERROR + 重试）
├── SubjectFactory.java                package-private：领域对象 → ScrapeSubject
├── NfoParser.java                     package-private：.nfo / metadata.json 解析（纯逻辑）
├── LocalNfoProvider.java              package-private
├── FilenameProvider.java              package-private：兜底，永远成功
├── TitleSimilarity.java               package-private：字符二元组 Dice 系数（纯逻辑）
├── ResolutionResult.java              package-private record
├── MetadataResolver.java              package-private：提供者链编排
├── MetadataProperties.java            package-private：@ConfigurationProperties
├── HttpProviderSupport.java           package-private：RestClient + UA + 客户端限流
├── ProviderCacheConfig.java           package-private：@EnableCaching + ConcurrentMapCacheManager
├── BangumiProvider.java               package-private
├── TmdbProvider.java                  package-private
├── ScrapeCandidateRecord.java         public record → 表 scrape_candidate
├── ScrapeCandidateStore.java          package-private：JdbcTemplate 读写
├── ScrapeCandidateService.java        public API：列表、确认、忽略
├── MetadataFetchJobHandler.java       package-private：METADATA_FETCH
├── MetadataTrigger.java               public API：排队入口
├── MetadataEventListener.java         package-private：订阅领域事件 + 扫描完成补齐
└── web/
    ├── MetadataDto.java
    ├── MetadataEditController.java    用户编辑与字段锁定
    └── ScrapeCandidateController.java 候选队列

src/main/java/com/mymedia/shared/       （新增到既有模块）
├── ScrapeStatus.java                  public 枚举
├── MetadataFields.java                public：标准字段名常量
├── MetadataPatch.java                 public record
└── FieldMergePolicy.java              public：跳过锁定字段与空值

src/main/resources/db/migration/
├── V10__derived_asset.sql
└── V11__scrape_candidate.sql

src/test/java/com/mymedia/preview/
├── DerivedAssetStorageTest.java       集成
├── DerivedAssetCascadeTest.java       集成：ON DELETE SET NULL 的证据
├── FfprobeParserTest.java             纯单元
├── MediaCommandsTest.java             纯单元
├── SleepingProcess.java               测试夹具：可控时长的子进程
├── ProcessCommandRunnerTest.java      纯单元（用当前 JVM 起子进程）
├── StubCommandRunner.java             测试夹具：桩 runner
├── WebVttWriterTest.java              纯单元
├── VideoPreviewJobTest.java           集成
├── ImagePreviewJobTest.java           集成
├── SpriteJobTest.java                 集成
├── PreviewWiringTest.java             集成：事件接线 + 全量重建
├── AssetControllerTest.java           集成
└── FfprobeSmokeTest.java              集成，assumeTrue 真机可用才跑

src/test/java/com/mymedia/metadata/
├── NfoParserTest.java                 纯单元
├── TitleSimilarityTest.java           纯单元
├── LocalNfoProviderTest.java          纯单元（临时目录）
├── MetadataResolverTest.java          纯单元（手写替身提供者）
├── StubHttpServer.java                测试夹具
├── BangumiProviderTest.java           纯单元（JDK HttpServer 桩）
├── TmdbProviderTest.java              纯单元（JDK HttpServer 桩）
├── MetadataWriteBackTest.java         集成：locked_fields 保护
└── MetadataFetchJobTest.java          集成：端到端 + 候选队列

src/test/java/com/mymedia/shared/
└── FieldMergePolicyTest.java          纯单元
```

---

## Task 1: `derived_asset` 表与派生资源存储

**Files:**
- Create: `src/main/resources/db/migration/V10__derived_asset.sql`
- Create: `src/main/java/com/mymedia/preview/package-info.java`
- Create: `src/main/java/com/mymedia/preview/DerivedAssetKind.java`
- Create: `src/main/java/com/mymedia/preview/DerivedAsset.java`
- Create: `src/main/java/com/mymedia/preview/DerivedAssetRepository.java`
- Create: `src/main/java/com/mymedia/preview/PreviewProperties.java`
- Create: `src/main/java/com/mymedia/preview/DerivedAssetStorage.java`
- Create: `src/main/java/com/mymedia/preview/DerivedAssetService.java`
- Modify: `src/main/resources/application.yml`（追加 `mymedia.preview` 配置块）
- Test: `src/test/java/com/mymedia/preview/DerivedAssetStorageTest.java`
- Test: `src/test/java/com/mymedia/preview/DerivedAssetCascadeTest.java`

**Interfaces:**
- Consumes: `MediaLibrary`、`LibraryService`、`LibraryDomain`（计划 01 Task 7）、`ScannedFile`、`ScannedFileQueryService`（计划 02 Task 1）、`AbstractIntegrationTest`（计划 01 Task 4）
- Produces:
  - `public enum DerivedAssetKind { COVER, THUMBNAIL, SPRITE_SHEET, SPRITE_VTT }`
  - `public class DerivedAsset` — getter：`Long getId()`、`DerivedAssetKind getKind()`、`Long getSourceScannedFileId()`、`String getRelativePath()`、`Integer getWidth()`、`Integer getHeight()`、`long getSizeBytes()`、`Instant getGeneratedAt()`
  - `public class DerivedAssetService`
    - `public Path prepare(DerivedAssetKind kind, Long sourceScannedFileId) throws IOException` — 建好父目录，返回**绝对**输出路径
    - `public DerivedAsset record(DerivedAssetKind kind, Long sourceScannedFileId, Integer width, Integer height)` — 幂等登记（同 source+kind 重复调用只更新，不新建）
    - `public Optional<DerivedAsset> find(DerivedAssetKind kind, Long sourceScannedFileId)`
    - `public DerivedAsset getById(Long id)`
    - `public Path pathOf(DerivedAsset asset)`

### 为什么用 `source_scanned_file_id` 做路径分片而不是 `derived_asset.id`

两级分片的目的是避免单目录堆几十万文件。分片键必须在**写文件之前**就知道——而 `derived_asset.id` 要等插入之后才有。用来源文件 id 分片则是天然确定的，还顺带带来一个好处：**重新生成会覆盖同一个路径**，与 `UNIQUE (source_scanned_file_id, kind)` 完全对齐，不会留下孤儿文件。

- [ ] **Step 1: 写会失败的存储测试**

`src/test/java/com/mymedia/preview/DerivedAssetStorageTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedAssetStorageTest extends AbstractIntegrationTest {

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 直接插一行 scanned_file 当来源。本任务还没有扫描链路可用，
     * 而 derived_asset 只需要一个合法的外键目标。
     */
    private Long insertScannedFile() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          extension, status, first_seen_at, last_seen_at)
                VALUES (?, ?, 1024, now(), 'mp4', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId(), "a/b/" + UUID.randomUUID() + ".mp4");
    }

    @Test
    void shardsPathIntoTwoLevelsBySourceFileId() throws IOException {
        Long sourceId = insertScannedFile();

        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);

        // covers/{id % 100}/{id}-cover.jpg
        assertThat(output.getFileName().toString()).isEqualTo(sourceId + "-cover.jpg");
        assertThat(output.getParent().getFileName().toString()).isEqualTo(String.valueOf(sourceId % 100));
        assertThat(output.getParent().getParent().getFileName().toString()).isEqualTo("covers");
        // 父目录必须已经建好，生成器直接写就行
        assertThat(Files.isDirectory(output.getParent())).isTrue();
    }

    @Test
    void recordsAssetWithSizeReadFromDisk() throws IOException {
        Long sourceId = insertScannedFile();
        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);
        Files.writeString(output, "0123456789", StandardCharsets.UTF_8);

        DerivedAsset asset = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        assertThat(asset.getId()).isNotNull();
        assertThat(asset.getKind()).isEqualTo(DerivedAssetKind.COVER);
        assertThat(asset.getSizeBytes()).isEqualTo(10L);
        assertThat(asset.getWidth()).isEqualTo(640);
        assertThat(asset.getRelativePath()).isEqualTo("covers/" + (sourceId % 100) + "/" + sourceId + "-cover.jpg");
        assertThat(assetService.pathOf(asset)).isEqualTo(output);
    }

    @Test
    void reRecordingSameSourceAndKindUpdatesInPlace() throws IOException {
        Long sourceId = insertScannedFile();
        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);
        Files.writeString(output, "old", StandardCharsets.UTF_8);
        DerivedAsset first = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        Files.writeString(output, "a much longer replacement", StandardCharsets.UTF_8);
        DerivedAsset second = assetService.record(DerivedAssetKind.COVER, sourceId, 800, 450);

        // 重新生成不能产生第二行——否则 cover_asset_id 会指向一个已被覆盖的旧文件
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getWidth()).isEqualTo(800);
        assertThat(second.getSizeBytes()).isEqualTo(25L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?",
                Integer.class, sourceId)).isEqualTo(1);
    }

    @Test
    void differentKindsCoexistForSameSource() throws IOException {
        Long sourceId = insertScannedFile();
        Files.writeString(assetService.prepare(DerivedAssetKind.COVER, sourceId), "c");
        Files.writeString(assetService.prepare(DerivedAssetKind.SPRITE_VTT, sourceId), "v");

        assetService.record(DerivedAssetKind.COVER, sourceId, null, null);
        assetService.record(DerivedAssetKind.SPRITE_VTT, sourceId, null, null);

        Optional<DerivedAsset> vtt = assetService.find(DerivedAssetKind.SPRITE_VTT, sourceId);
        assertThat(vtt).isPresent();
        assertThat(vtt.get().getRelativePath()).endsWith("-sprite.vtt");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?",
                Integer.class, sourceId)).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 写会失败的级联测试**

这个测试是 spec §6.2「派生目录删光后可全量重建」的**证据**。

`src/test/java/com/mymedia/preview/DerivedAssetCascadeTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedAssetCascadeTest extends AbstractIntegrationTest {

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletingDerivedAssetsNullsOutCoverReferencesInsteadOfFailing() throws IOException {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());

        Long sourceId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          extension, status, first_seen_at, last_seen_at)
                VALUES (?, ?, 1024, now(), 'mp4', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId(), UUID.randomUUID() + ".mp4");

        Files.writeString(assetService.prepare(DerivedAssetKind.COVER, sourceId), "cover");
        DerivedAsset cover = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, cover_asset_id)
                VALUES (?, 'MOVIE', '测试影片', '测试影片', ?)
                RETURNING id
                """, Long.class, library.getId(), cover.getId());

        // 清空派生资源：不需要先解引用，外键 ON DELETE SET NULL 会替我们做
        jdbc.update("DELETE FROM derived_asset");

        Long remaining = jdbc.queryForObject(
                "SELECT cover_asset_id FROM video_item WHERE id = ?", Long.class, itemId);
        assertThat(remaining).isNull();
        // 用户数据一行不少
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item WHERE id = ?", Integer.class, itemId)).isEqualTo(1);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='DerivedAsset*Test' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`DerivedAssetService` 等类不存在。

- [ ] **Step 4: 写迁移脚本**

`src/main/resources/db/migration/V10__derived_asset.sql`：

```sql
-- ============================================================
-- 派生资源：封面、缩略图、雪碧图、雪碧图 VTT。
-- 全部是「生成物」而非扫描所得，因此与 scanned_file 分表存放，
-- 目录独立于媒体库根路径，删光后可全量重建。详见 spec 6.2。
-- ============================================================

CREATE TABLE derived_asset (
    id                     BIGSERIAL PRIMARY KEY,
    kind                   VARCHAR(16) NOT NULL,
    -- 所有派生资源都从某一个原始文件生成（视频抽帧 / 漫画首页 / 图集首图），
    -- 因此这是单一外键，没有多态。
    source_scanned_file_id BIGINT      NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    -- 相对于派生资源根目录（mymedia.preview.root），不含根路径本身
    relative_path          TEXT        NOT NULL,
    width                  INT,
    height                 INT,
    size_bytes             BIGINT      NOT NULL,
    generated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_derived_asset_kind CHECK (
        kind IN ('COVER', 'THUMBNAIL', 'SPRITE_SHEET', 'SPRITE_VTT'))
);

-- 一个来源文件的每种派生资源只有一份。重新生成是 UPDATE，不是再插一行。
ALTER TABLE derived_asset
    ADD CONSTRAINT uq_derived_asset_source_kind UNIQUE (source_scanned_file_id, kind);
ALTER TABLE derived_asset
    ADD CONSTRAINT uq_derived_asset_path UNIQUE (relative_path);

-- ------------------------------------------------------------
-- 现在才给各处的 cover_asset_id 补外键。
--
-- V6 / V8 建表时 derived_asset 还不存在（预览是 P8 的事），所以当时
-- 只有列没有约束。迁移的顺序本身就说明了模块的构建顺序。
--
-- ON DELETE SET NULL 是「派生目录删光后可全量重建」这句承诺的实现：
-- DELETE FROM derived_asset 之后所有封面引用自动置空，
-- 扫描完成时的补齐逻辑会把它们重新排队生成，用户数据一行不动。
-- ------------------------------------------------------------
ALTER TABLE video_item ADD CONSTRAINT fk_video_item_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE video_group ADD CONSTRAINT fk_video_group_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE collection ADD CONSTRAINT fk_collection_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE image_node ADD CONSTRAINT fk_image_node_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;

-- 补齐逻辑按「还没有封面」筛选，部分索引让这个查询不必全表扫描
CREATE INDEX idx_video_item_without_cover ON video_item (library_id)
    WHERE cover_asset_id IS NULL;
CREATE INDEX idx_image_node_without_cover ON image_node (library_id)
    WHERE cover_asset_id IS NULL;
```

- [ ] **Step 5: 写模块声明、枚举与实体**

`src/main/java/com/mymedia/preview/package-info.java`：

```java
/**
 * 派生资源生成：封面、缩略图、进度条雪碧图。
 *
 * <p><b>依赖方向是单向的</b>：本模块订阅 {@code video} / {@code image} 的领域事件
 * 并调用它们的公开写回 API，两个领域模块<b>绝不</b>反向引用本模块。
 * 这一点由 {@code ModularityTests} 强制。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Preview",
        allowedDependencies = {"shared", "library", "jobs", "scan"})
package com.mymedia.preview;
```

> 后续任务会往 `allowedDependencies` 里增补 `video`、`image` 与它们的事件命名接口。每次增补都在对应任务里显式列出，**不要提前写上还不存在的命名接口**——Modulith 会因为找不到它而让 `verify()` 失败。

`src/main/java/com/mymedia/preview/DerivedAssetKind.java`：

```java
package com.mymedia.preview;

/** 派生资源的种类。取值与 {@code derived_asset.kind} 的 CHECK 约束一一对应。 */
public enum DerivedAssetKind {

    /** 封面：视频抽帧 / 漫画首页 / 图集首图，等比缩到 640 宽。 */
    COVER("covers", "cover", "jpg"),

    /** 缩略图：从封面再缩到 320 宽，列表页用。 */
    THUMBNAIL("thumbs", "thumb", "jpg"),

    /** 进度条预览雪碧图：固定 100 帧、10 × 10 单张。 */
    SPRITE_SHEET("sprites", "sprite", "jpg"),

    /** 雪碧图的 WebVTT 索引，播放器按它把时间点映射到图块。 */
    SPRITE_VTT("sprites", "sprite", "vtt");

    private final String directory;
    private final String suffix;
    private final String extension;

    DerivedAssetKind(String directory, String suffix, String extension) {
        this.directory = directory;
        this.suffix = suffix;
        this.extension = extension;
    }

    String directory() {
        return directory;
    }

    /** {@code {sourceFileId}-cover.jpg} 这样的文件名。 */
    String fileName(Long sourceScannedFileId) {
        return sourceScannedFileId + "-" + suffix + "." + extension;
    }

    /** public：Task 6 的 {@code preview.web.AssetController} 在嵌套包里，需要读得到。 */
    public String contentType() {
        return this == SPRITE_VTT ? "text/vtt" : "image/jpeg";
    }
}
```

`src/main/java/com/mymedia/preview/DerivedAsset.java`：

```java
package com.mymedia.preview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "derived_asset")
public class DerivedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DerivedAssetKind kind;

    @Column(name = "source_scanned_file_id", nullable = false)
    private Long sourceScannedFileId;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    private Integer width;

    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    protected DerivedAsset() {
        // JPA 要求的无参构造器
    }

    DerivedAsset(DerivedAssetKind kind, Long sourceScannedFileId, String relativePath) {
        this.kind = kind;
        this.sourceScannedFileId = sourceScannedFileId;
        this.relativePath = relativePath;
    }

    void refresh(Integer width, Integer height, long sizeBytes) {
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.generatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public DerivedAssetKind getKind() { return kind; }
    public Long getSourceScannedFileId() { return sourceScannedFileId; }
    public String getRelativePath() { return relativePath; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getGeneratedAt() { return generatedAt; }
}
```

`src/main/java/com/mymedia/preview/DerivedAssetRepository.java`：

```java
package com.mymedia.preview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface DerivedAssetRepository extends JpaRepository<DerivedAsset, Long> {

    Optional<DerivedAsset> findByKindAndSourceScannedFileId(DerivedAssetKind kind, Long sourceScannedFileId);

    List<DerivedAsset> findBySourceScannedFileId(Long sourceScannedFileId);
}
```

- [ ] **Step 6: 写配置属性与存储**

`src/main/java/com/mymedia/preview/PreviewProperties.java`：

```java
package com.mymedia.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 预览生成的全部可调项。
 *
 * <p>{@code root} 独立于任何媒体库路径——派生资源可以整个删掉重建，
 * 而媒体库里放的是用户不可替代的原始文件，两者绝不能混在一起。
 */
@ConfigurationProperties(prefix = "mymedia.preview")
record PreviewProperties(
        String root,
        String ffmpegPath,
        String ffprobePath,
        Duration commandTimeout,
        int coverWidth,
        int thumbnailWidth,
        int spriteFrames,
        int spriteColumns,
        int spriteTileWidth,
        int spriteMinDurationSeconds) {

    PreviewProperties {
        root = root == null ? "./data/derived" : root;
        ffmpegPath = ffmpegPath == null ? "ffmpeg" : ffmpegPath;
        ffprobePath = ffprobePath == null ? "ffprobe" : ffprobePath;
        commandTimeout = commandTimeout == null ? Duration.ofMinutes(2) : commandTimeout;
        coverWidth = coverWidth <= 0 ? 640 : coverWidth;
        thumbnailWidth = thumbnailWidth <= 0 ? 320 : thumbnailWidth;
        spriteFrames = spriteFrames <= 0 ? 100 : spriteFrames;
        spriteColumns = spriteColumns <= 0 ? 10 : spriteColumns;
        spriteTileWidth = spriteTileWidth <= 0 ? 160 : spriteTileWidth;
        spriteMinDurationSeconds = spriteMinDurationSeconds <= 0 ? 10 : spriteMinDurationSeconds;
    }
}
```

`src/main/java/com/mymedia/preview/DerivedAssetStorage.java`：

```java
package com.mymedia.preview;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 派生资源在磁盘上的布局。
 *
 * <p>路径形如 {@code covers/37/1337-cover.jpg}——按来源文件 id 取模做两级分片，
 * 避免几十万个文件堆在一个目录里（ext4 与 NTFS 在单目录十万级文件时目录项查找
 * 会明显退化）。
 *
 * <p>分片键用<b>来源文件 id</b> 而不是 {@code derived_asset.id}：前者在写文件之前
 * 就已知，后者要等插入之后才有。附带好处是重新生成会覆盖同一个路径，与
 * {@code UNIQUE (source_scanned_file_id, kind)} 完全对齐，不会留下孤儿文件。
 */
@Component
class DerivedAssetStorage {

    private static final int SHARD_COUNT = 100;

    private final Path root;

    DerivedAssetStorage(PreviewProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    String relativePathOf(DerivedAssetKind kind, Long sourceScannedFileId) {
        long shard = Math.floorMod(sourceScannedFileId, SHARD_COUNT);
        return kind.directory() + "/" + shard + "/" + kind.fileName(sourceScannedFileId);
    }

    Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    /** 建好父目录并返回输出路径，生成器直接往这个路径写就行。 */
    Path prepare(DerivedAssetKind kind, Long sourceScannedFileId) throws IOException {
        Path target = resolve(relativePathOf(kind, sourceScannedFileId));
        Files.createDirectories(target.getParent());
        return target;
    }
}
```

- [ ] **Step 7: 写服务**

`src/main/java/com/mymedia/preview/DerivedAssetService.java`：

```java
package com.mymedia.preview;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 派生资源的登记与查询，是 {@code preview} 模块对外的资源入口。
 */
@Service
public class DerivedAssetService {

    private final DerivedAssetRepository repository;
    private final DerivedAssetStorage storage;

    DerivedAssetService(DerivedAssetRepository repository, DerivedAssetStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** 建好目录并返回该资源应当写入的绝对路径。 */
    public Path prepare(DerivedAssetKind kind, Long sourceScannedFileId) throws IOException {
        return storage.prepare(kind, sourceScannedFileId);
    }

    /**
     * 把已经写到磁盘上的文件登记入库，大小从磁盘现读。
     *
     * <p><b>幂等</b>：同一个 (来源文件, 种类) 重复调用只更新既有行。
     * 重新生成不能产生第二行，否则 {@code cover_asset_id} 会指向一个
     * 已经被覆盖掉的旧文件。
     */
    @Transactional
    public DerivedAsset record(DerivedAssetKind kind, Long sourceScannedFileId,
                               Integer width, Integer height) {
        String relativePath = storage.relativePathOf(kind, sourceScannedFileId);
        Path file = storage.resolve(relativePath);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("派生资源文件不存在或不可读: " + file, e);
        }

        DerivedAsset asset = repository
                .findByKindAndSourceScannedFileId(kind, sourceScannedFileId)
                .orElseGet(() -> new DerivedAsset(kind, sourceScannedFileId, relativePath));
        asset.refresh(width, height, size);
        return repository.saveAndFlush(asset);
    }

    @Transactional(readOnly = true)
    public Optional<DerivedAsset> find(DerivedAssetKind kind, Long sourceScannedFileId) {
        return repository.findByKindAndSourceScannedFileId(kind, sourceScannedFileId);
    }

    @Transactional(readOnly = true)
    public DerivedAsset getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到派生资源 id=" + id));
    }

    public Path pathOf(DerivedAsset asset) {
        return storage.resolve(asset.getRelativePath());
    }
}
```

- [ ] **Step 8: 追加配置并启用 `@ConfigurationProperties` 扫描**

在 `src/main/resources/application.yml` 的 `mymedia:` 块下追加（与既有的 `admin:`、`jobs:` 平级）：

```yaml
  preview:
    root: ./data/derived        # 派生资源根目录，独立于媒体库路径，可整个删掉重建
    ffmpeg-path: ffmpeg         # 镜像里烘焙好，本机不装也能跑测试
    ffprobe-path: ffprobe
    command-timeout: PT2M       # 单次外部进程调用的上限
    cover-width: 640
    thumbnail-width: 320
    sprite-frames: 100          # 固定 100 帧，永远单张图
    sprite-columns: 10          # 10 × 10
    sprite-tile-width: 160
    sprite-min-duration-seconds: 10   # 太短的视频不值得做雪碧图
```

修改 `src/main/java/com/mymedia/MyMediaApplication.java`，让 record 形式的 `@ConfigurationProperties` 被扫描到：

```java
package com.mymedia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MyMediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMediaApplication.class, args);
    }
}
```

- [ ] **Step 9: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='DerivedAsset*Test,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`DerivedAssetStorageTest` 4 个用例、`DerivedAssetCascadeTest` 1 个用例、`ModularityTests` 全部通过。

- [ ] **Step 10: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V10__derived_asset.sql \
        src/main/java/com/mymedia/preview src/main/java/com/mymedia/MyMediaApplication.java \
        src/main/resources/application.yml src/test/java/com/mymedia/preview
git commit -m "feat: 添加 derived_asset 表与派生资源存储

路径按来源文件 id 两级分片——分片键在写文件前就已知，
且重新生成覆盖同一路径，与 UNIQUE(source, kind) 对齐。

cover_asset_id 的外键到这一版才补上（V6/V8 建表时 derived_asset
还不存在）。ON DELETE SET NULL 是「派生目录删光后可全量重建」
这句承诺的实现，DerivedAssetCascadeTest 是它的证据。"
```

---

## Task 2: 外部进程运行器与 ffprobe 输出解析

**Files:**
- Create: `src/main/java/com/mymedia/preview/CommandResult.java`
- Create: `src/main/java/com/mymedia/preview/CommandRunner.java`
- Create: `src/main/java/com/mymedia/preview/ProcessCommandRunner.java`
- Create: `src/main/java/com/mymedia/preview/FfprobeOutput.java`
- Create: `src/main/java/com/mymedia/preview/FfprobeParser.java`
- Create: `src/main/java/com/mymedia/preview/MediaCommands.java`
- Test: `src/test/java/com/mymedia/preview/SleepingProcess.java`
- Test: `src/test/java/com/mymedia/preview/ProcessCommandRunnerTest.java`
- Test: `src/test/java/com/mymedia/preview/FfprobeParserTest.java`
- Test: `src/test/java/com/mymedia/preview/MediaCommandsTest.java`

**Interfaces:**
- Consumes: `PreviewProperties`（Task 1）
- Produces:
  - `public record CommandResult(int exitCode, String stdout, String stderr)` — 附 `boolean succeeded()`
  - `public interface CommandRunner` — `CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException`
    **这是外部进程调用的唯一入口。任何直接 `new ProcessBuilder(...)` 的实现都不接受**——它会让整个模块无法在没装 ffmpeg 的机器上测试。
  - `record FfprobeOutput(Integer durationSeconds, Integer width, Integer height, String videoCodec, String audioCodec, Long bitrate, String container, String rawJson)`（package-private）
  - `class FfprobeParser`（package-private）— `static FfprobeOutput parse(String json)`
  - `class MediaCommands`（package-private）— `static List<String> probe(...)`、`static List<String> coverFrame(...)`、`static List<String> spriteSheet(...)`

- [ ] **Step 1: 写会失败的 ffprobe 解析测试**

fixture 取自 ffprobe 的真实输出形状（`-print_format json -show_format -show_streams`）。

`src/test/java/com/mymedia/preview/FfprobeParserTest.java`：

```java
package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FfprobeParserTest {

    private static final String TYPICAL_MP4 = """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "duration": "596.474000",
                  "bit_rate": "1970198"
                },
                {
                  "index": 1,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "sample_rate": "48000",
                  "channels": 2
                }
              ],
              "format": {
                "filename": "/media/movies/BigBuckBunny.mp4",
                "nb_streams": 2,
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                "duration": "596.474000",
                "size": "158008374",
                "bit_rate": "2119721"
              }
            }
            """;

    @Test
    void readsDurationRoundedToSeconds() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).durationSeconds()).isEqualTo(596);
    }

    @Test
    void readsGeometryAndCodecsFromTheRightStreams() {
        FfprobeOutput output = FfprobeParser.parse(TYPICAL_MP4);

        assertThat(output.width()).isEqualTo(1920);
        assertThat(output.height()).isEqualTo(1080);
        assertThat(output.videoCodec()).isEqualTo("h264");
        assertThat(output.audioCodec()).isEqualTo("aac");
    }

    @Test
    void takesFirstTokenOfFormatNameAsContainer() {
        // format_name 是一串同义容器名，取第一个作为展示值
        assertThat(FfprobeParser.parse(TYPICAL_MP4).container()).isEqualTo("mov");
    }

    @Test
    void prefersFormatBitrateOverStreamBitrate() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).bitrate()).isEqualTo(2119721L);
    }

    @Test
    void keepsRawJsonForLaterInspection() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).rawJson()).contains("BigBuckBunny.mp4");
    }

    @Test
    void fallsBackToVideoStreamDurationWhenFormatHasNone() {
        String noFormatDuration = """
                {
                  "streams": [
                    {"codec_type": "video", "codec_name": "vp9", "width": 640, "height": 360,
                     "duration": "12.500000"}
                  ],
                  "format": {"format_name": "matroska,webm"}
                }
                """;

        FfprobeOutput output = FfprobeParser.parse(noFormatDuration);

        assertThat(output.durationSeconds()).isEqualTo(13);
        assertThat(output.container()).isEqualTo("matroska");
        assertThat(output.bitrate()).isNull();
    }

    @Test
    void audioOnlyFileHasNoGeometryButStillParses() {
        String audioOnly = """
                {
                  "streams": [{"codec_type": "audio", "codec_name": "flac"}],
                  "format": {"format_name": "flac", "duration": "180.000000"}
                }
                """;

        FfprobeOutput output = FfprobeParser.parse(audioOnly);

        assertThat(output.width()).isNull();
        assertThat(output.height()).isNull();
        assertThat(output.videoCodec()).isNull();
        assertThat(output.audioCodec()).isEqualTo("flac");
        assertThat(output.durationSeconds()).isEqualTo(180);
    }

    @Test
    void unparseableOutputRaisesInsteadOfSilentlyReturningNulls() {
        // 静默返回空值会让上游以为探测成功，进而写一堆 null 进 video_file
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> FfprobeParser.parse("not json at all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ffprobe");
    }

    @Test
    void durationOfNAIsTreatedAsUnknown() {
        // 部分流式容器会给出字面量 "N/A"
        String naDuration = """
                {
                  "streams": [{"codec_type": "video", "codec_name": "h264", "width": 4, "height": 4}],
                  "format": {"format_name": "mpegts", "duration": "N/A"}
                }
                """;

        assertThat(FfprobeParser.parse(naDuration).durationSeconds()).isNull();
    }
}
```

- [ ] **Step 2: 写会失败的命令行构造测试**

`src/test/java/com/mymedia/preview/MediaCommandsTest.java`：

```java
package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCommandsTest {

    private static final Path INPUT = Path.of("/media", "a.mkv");
    private static final Path OUTPUT = Path.of("/derived", "a.jpg");

    @Test
    void probeAsksForJsonWithFormatAndStreams() {
        assertThat(MediaCommands.probe("ffprobe", INPUT)).containsExactly(
                "ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", INPUT.toString());
    }

    @Test
    void coverFrameSeeksBeforeInputForFastSeeking() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, 600, 640, OUTPUT);

        // -ss 放在 -i 前面走的是关键帧快速定位；放后面会让 ffmpeg 从头解码到该点，
        // 一部两小时的电影能慢上几十秒。顺序不是风格问题。
        assertThat(command.indexOf("-ss")).isLessThan(command.indexOf("-i"));
        assertThat(command).containsExactly(
                "ffmpeg", "-y", "-ss", "60.000", "-i", INPUT.toString(),
                "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "3", OUTPUT.toString());
    }

    @Test
    void coverFrameOfVeryShortVideoStartsAtZero() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, 3, 640, OUTPUT);

        assertThat(command).contains("0.300");
    }

    @Test
    void coverFrameOfUnknownDurationStartsAtZero() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, null, 640, OUTPUT);

        assertThat(command.get(command.indexOf("-ss") + 1)).isEqualTo("0.000");
    }

    @Test
    void spriteSheetUsesFpsThatYieldsExactlyTheRequestedFrameCount() {
        // 100 帧 / 600 秒 = 每 6 秒一帧
        List<String> command = MediaCommands.spriteSheet("ffmpeg", INPUT, 600, 100, 10, 160, OUTPUT);

        assertThat(command).containsExactly(
                "ffmpeg", "-y", "-i", INPUT.toString(),
                "-vf", "fps=0.166667,scale=160:-2,tile=10x10",
                "-frames:v", "1", OUTPUT.toString());
    }

    @Test
    void spriteSheetOfShortVideoStillProducesOneSheet() {
        List<String> command = MediaCommands.spriteSheet("ffmpeg", INPUT, 20, 100, 10, 160, OUTPUT);

        assertThat(command).contains("fps=5.000000,scale=160:-2,tile=10x10");
    }

    @Test
    void binaryPathIsConfigurableForContainersThatShipItElsewhere() {
        assertThat(MediaCommands.probe("/usr/local/bin/ffprobe", INPUT))
                .first().isEqualTo("/usr/local/bin/ffprobe");
    }
}
```

- [ ] **Step 3: 写会失败的进程运行器测试**

用**当前 JVM** 起子进程，不依赖 `sleep` / `ping` 这些平台各异的命令——测试在 Windows 开发机与 Linux 容器里都要过。

`src/test/java/com/mymedia/preview/SleepingProcess.java`：

```java
package com.mymedia.preview;

/**
 * 测试夹具：一个睡指定毫秒数再退出的子进程。
 *
 * <p>用它而不是 {@code sleep} / {@code ping}——后者在 Windows 与 Linux 上
 * 名字和参数都不一样，而当前 JVM 一定存在。
 */
public final class SleepingProcess {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("started");
        System.out.flush();
        Thread.sleep(Long.parseLong(args[0]));
        System.out.println("finished");
    }
}
```

`src/test/java/com/mymedia/preview/ProcessCommandRunnerTest.java`：

```java
package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    /** 当前 JVM 的 java 可执行文件，跨平台可用。 */
    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static List<String> sleepFor(long millis) {
        return List.of(javaBinary(), "-cp", System.getProperty("java.class.path"),
                SleepingProcess.class.getName(), String.valueOf(millis));
    }

    @Test
    void capturesStdoutAndExitCode() throws Exception {
        CommandResult result = runner.run(sleepFor(0), Duration.ofSeconds(30));

        assertThat(result.exitCode()).isZero();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("finished");
    }

    @Test
    void capturesNonZeroExitWithoutThrowing() throws Exception {
        // 缺少参数 → SleepingProcess 抛异常退出，退出码非 0，stderr 有栈
        List<String> command = List.of(javaBinary(), "-cp", System.getProperty("java.class.path"),
                SleepingProcess.class.getName());

        CommandResult result = runner.run(command, Duration.ofSeconds(30));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.stderr()).contains("ArrayIndexOutOfBoundsException");
    }

    @Test
    void killsProcessThatOutlivesItsTimeout() {
        // 没有超时的话，一个卡死的 ffmpeg 会永远占住一个任务租约
        assertThatThrownBy(() -> runner.run(sleepFor(60_000), Duration.ofMillis(500)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void missingBinaryFailsFastWithTheCommandInTheMessage() {
        assertThatThrownBy(() -> runner.run(
                List.of("definitely-not-a-real-binary-xyz"), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("definitely-not-a-real-binary-xyz");
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='FfprobeParserTest,MediaCommandsTest,ProcessCommandRunnerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`FfprobeParser`、`MediaCommands`、`ProcessCommandRunner` 不存在。

- [ ] **Step 5: 实现 `CommandRunner`**

`src/main/java/com/mymedia/preview/CommandResult.java`：

```java
package com.mymedia.preview;

/** 一次外部进程调用的结果。 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    public boolean succeeded() {
        return exitCode == 0;
    }
}
```

`src/main/java/com/mymedia/preview/CommandRunner.java`：

```java
package com.mymedia.preview;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 外部进程调用的唯一入口。
 *
 * <p><b>这个接口存在的全部理由是可测试性。</b> ffmpeg / ffprobe 烘焙在应用镜像里，
 * 开发机上不一定装了；把进程调用收敛到一个可注入的接口后，任务处理器的集成测试
 * 只需提供一个按输出路径写假文件的桩实现，就能在任何机器上跑。
 *
 * <p>任何直接 {@code new ProcessBuilder(...)} 的代码都会让这条路走不通。
 */
public interface CommandRunner {

    /**
     * 同步执行命令并等待结束。
     *
     * @throws IOException 进程无法启动，或超过 {@code timeout} 仍未结束（此时进程已被强制终止）
     */
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
}
```

`src/main/java/com/mymedia/preview/ProcessCommandRunner.java`：

```java
package com.mymedia.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link ProcessBuilder} 执行外部命令。
 *
 * <p>两个必须处理的坑：
 * <ol>
 *   <li><b>管道必须被读走。</b> ffmpeg 往 stderr 写进度，管道缓冲区满了它就会阻塞，
 *       表现为"卡死"。这里各起一个线程把两个流读干。虚拟线程让这个代价可以忽略。</li>
 *   <li><b>超时必须真的杀进程。</b> 只 {@code waitFor(timeout)} 而不 destroy，
 *       僵死的 ffmpeg 会一直占着任务租约。</li>
 * </ol>
 */
@Component
class ProcessCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessCommandRunner.class);

    @Override
    public CommandResult run(List<String> command, Duration timeout)
            throws IOException, InterruptedException {

        log.debug("执行外部命令: {}", String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IOException("无法启动外部进程: " + String.join(" ", command), e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            outReader.join();
            errReader.join();
            throw new IOException("外部进程超时（" + timeout + "），已强制终止: "
                    + String.join(" ", command));
        }

        outReader.join();
        errReader.join();
        return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private static Thread drain(InputStream stream, StringBuilder sink) {
        return Thread.ofVirtual().start(() -> {
            try (InputStream in = stream) {
                sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 进程被强杀时读流会断，这不是错误
            }
        });
    }
}
```

- [ ] **Step 6: 实现 ffprobe 解析**

`src/main/java/com/mymedia/preview/FfprobeOutput.java`：

```java
package com.mymedia.preview;

/**
 * ffprobe 探测结果中本项目关心的部分。
 *
 * <p>每个字段都可能为 {@code null}——媒体文件的容器五花八门，
 * 缺时长、缺比特率、纯音频都是正常情况，不是错误。
 */
record FfprobeOutput(
        Integer durationSeconds,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        Long bitrate,
        String container,
        String rawJson) {
}
```

`src/main/java/com/mymedia/preview/FfprobeParser.java`：

```java
package com.mymedia.preview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 {@code ffprobe -print_format json -show_format -show_streams} 的输出
 * 解析成 {@link FfprobeOutput}。
 *
 * <p><b>纯函数，没有任何 I/O</b>——因此它的测试是喂字符串的单元测试，
 * 不需要机器上装 ffprobe。这正是把进程调用收敛到 {@link CommandRunner} 的收益。
 */
final class FfprobeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FfprobeParser() {
    }

    static FfprobeOutput parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 ffprobe 输出: " + preview(json), e);
        }
        if (!root.isObject() || !root.has("format")) {
            throw new IllegalArgumentException("ffprobe 输出缺少 format 节: " + preview(json));
        }

        JsonNode format = root.path("format");
        JsonNode video = firstStreamOfType(root, "video");
        JsonNode audio = firstStreamOfType(root, "audio");

        Double duration = asDouble(format.path("duration"));
        if (duration == null) {
            duration = asDouble(video.path("duration"));
        }

        return new FfprobeOutput(
                duration == null ? null : (int) Math.round(duration),
                asInt(video.path("width")),
                asInt(video.path("height")),
                textOf(video.path("codec_name")),
                textOf(audio.path("codec_name")),
                asLong(format.path("bit_rate")),
                firstToken(textOf(format.path("format_name"))),
                json);
    }

    private static JsonNode firstStreamOfType(JsonNode root, String codecType) {
        for (JsonNode stream : root.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asString(null))) {
                return stream;
            }
        }
        return MAPPER.nullNode();
    }

    /** {@code "mov,mp4,m4a,3gp,3g2,mj2"} → {@code "mov"}。 */
    private static String firstToken(String formatName) {
        if (formatName == null) {
            return null;
        }
        int comma = formatName.indexOf(',');
        return comma < 0 ? formatName : formatName.substring(0, comma);
    }

    private static String textOf(JsonNode node) {
        String value = node.asString(null);
        return value == null || value.isBlank() || "N/A".equals(value) ? null : value;
    }

    private static Integer asInt(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String preview(String json) {
        if (json == null) {
            return "(null)";
        }
        return json.length() <= 200 ? json : json.substring(0, 200) + "…";
    }
}
```

- [ ] **Step 7: 实现命令行构造**

`src/main/java/com/mymedia/preview/MediaCommands.java`：

```java
package com.mymedia.preview;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ffmpeg / ffprobe 的命令行构造。
 *
 * <p>单独成类是为了让参数顺序可以被单元测试断言——{@code -ss} 放在 {@code -i}
 * 前还是后是性能差几十倍的事，而这种错误在集成测试里根本看不出来（两种写法
 * 都能出图）。
 */
final class MediaCommands {

    /** 抽帧点取时长的十分之一：避开片头黑屏与厂标，又不至于剧透。 */
    private static final double COVER_POSITION_RATIO = 0.1;

    private MediaCommands() {
    }

    static List<String> probe(String ffprobePath, Path input) {
        return List.of(ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format", "-show_streams",
                input.toString());
    }

    /**
     * 抽一帧做封面。
     *
     * <p>{@code -ss} 必须在 {@code -i} <b>之前</b>：这时 ffmpeg 直接 seek 到最近的
     * 关键帧再开始解码；放在之后则是先从头解码、再丢弃前面的帧，一部两小时的
     * 电影要多花几十秒。
     */
    static List<String> coverFrame(String ffmpegPath, Path input, Integer durationSeconds,
                                   int width, Path output) {
        double position = durationSeconds == null ? 0.0 : durationSeconds * COVER_POSITION_RATIO;
        return List.of(ffmpegPath,
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", position),
                "-i", input.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + width + ":-2",
                "-q:v", "3",
                output.toString());
    }

    /**
     * 生成进度条预览用的雪碧图。
     *
     * <p>帧数固定（默认 100）、网格固定（10 × 10），于是<b>永远只有一张图、一个 VTT</b>，
     * 省掉多图分页的全部复杂度。抽帧间隔由 {@code fps = frames / duration} 反推，
     * 短片会得到大于 1 的 fps，长片会得到很小的 fps，两端都成立。
     *
     * <p>{@code scale=160:-2} 里的 {@code -2} 表示"高度按比例算，并取到 2 的倍数"——
     * 大多数编码器要求偶数尺寸。
     */
    static List<String> spriteSheet(String ffmpegPath, Path input, int durationSeconds,
                                    int frames, int columns, int tileWidth, Path output) {
        int rows = (int) Math.ceil((double) frames / columns);
        double fps = (double) frames / Math.max(durationSeconds, 1);
        String filter = String.format(Locale.ROOT, "fps=%.6f,scale=%d:-2,tile=%dx%d",
                fps, tileWidth, columns, rows);
        return List.of(ffmpegPath,
                "-y",
                "-i", input.toString(),
                "-vf", filter,
                "-frames:v", "1",
                output.toString());
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='FfprobeParserTest,MediaCommandsTest,ProcessCommandRunnerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，三个测试类共 19 个用例通过。

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/preview src/test/java/com/mymedia/preview
git commit -m "feat: 添加外部进程运行器与 ffprobe 输出解析

CommandRunner 存在的全部理由是可测试性：解析与命令行构造成为纯逻辑，
在没装 ffmpeg 的机器上也能完整测试。

-ss 放在 -i 之前是关键帧快速定位，有单元测试守住这个顺序——
两种写法都能出图，集成测试看不出差别。"
```

---

## Task 3: 视频预览生成（探测 → 封面 → 缩略图）

**Files:**
- Create: `src/main/java/com/mymedia/video/VideoProbeData.java`
- Create: `src/main/java/com/mymedia/video/VideoProbeStore.java`
- Modify: `src/main/java/com/mymedia/video/VideoCatalogService.java`（新增 3 个写回方法）
- Create: `src/main/java/com/mymedia/preview/SourceFileLocator.java`
- Create: `src/main/java/com/mymedia/preview/ImageScaler.java`
- Create: `src/main/java/com/mymedia/preview/PreviewTarget.java`
- Create: `src/main/java/com/mymedia/preview/PreviewTrigger.java`
- Create: `src/main/java/com/mymedia/preview/VideoPreviewGenerator.java`
- Create: `src/main/java/com/mymedia/preview/PreviewJobHandler.java`
- Modify: `src/main/java/com/mymedia/preview/package-info.java`（`allowedDependencies` 增补 `video`）
- Test: `src/test/java/com/mymedia/preview/StubCommandRunner.java`
- Test: `src/test/java/com/mymedia/preview/VideoPreviewJobTest.java`

**Interfaces:**
- Consumes: `DerivedAssetService`、`DerivedAssetKind`、`PreviewProperties`、`CommandRunner`、`MediaCommands`、`FfprobeParser`（Task 1、2）、`VideoFile`、`VideoItem`、`VideoCatalogService`（计划 03 Task 3、5）、`ScannedFile`、`ScannedFileQueryService`、`ScannedFileStatus`（计划 02 Task 1）、`LibraryService`（计划 01 Task 7）、`JobHandler`、`JobQueue`、`Job`（计划 01 Task 10、12）
- Produces:
  - `public record VideoProbeData(Integer durationSeconds, Integer width, Integer height, String videoCodec, String audioCodec, Long bitrate, String container, String rawJson)`（`com.mymedia.video`）
  - `VideoCatalogService` 新增：
    - `public void applyProbe(Long videoFileId, VideoProbeData probe)`
    - `public boolean assignCoverIfAbsent(Long itemId, Long assetId)` — 返回是否真的写入
    - `public List<Long> itemsWithoutCover(Long libraryId, int limit)`
  - `public class PreviewTrigger`
    - `public Long requestVideoPreview(Long videoFileId)`
    - `public Long requestImagePreview(Long imageNodeId)`
    - `public Long requestSprite(Long videoFileId)`
  - `class PreviewJobHandler implements JobHandler`（package-private）— `static final String JOB_TYPE = "PREVIEW_GENERATE"`

### 为什么探测与抽帧在同一个处理器里顺序执行

不知道时长就选不出抽帧点（封面取时长的 10%），所以探测必须先于抽帧。spec §6.2 的 job type 清单里本来也没有单独的 `PROBE` 类型——把它们拆成两个任务只会多一次调度往返和一次失败重试的语义分裂。

### 为什么 `assignCoverIfAbsent` 而不是 `assignCover`

判断"条目还没有封面"必须和写入是同一个原子操作，否则两个并发的 `PREVIEW_GENERATE`（同一条目的两个文件）会互相覆盖。一条 `UPDATE ... WHERE cover_asset_id IS NULL` 表达了全部意图，也省掉了在 `VideoItem` 上加 getter。

- [ ] **Step 1: 写桩 `CommandRunner`**

`src/test/java/com/mymedia/preview/StubCommandRunner.java`：

```java
package com.mymedia.preview;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的 {@link CommandRunner} 桩。
 *
 * <p>ffmpeg / ffprobe 烘焙在应用镜像里，开发机上不一定有；本桩按命令形状作答：
 * <ul>
 *   <li>带 {@code -print_format json} 的当作探测，从 stdout 返回预置的 JSON</li>
 *   <li>其余当作出图命令，<b>在命令行最后一项（输出路径）写一张真实的小 JPEG</b>——
 *       下游的缩略图与雪碧图几何计算真的会 {@code ImageIO.read} 它，写假字节过不了</li>
 * </ul>
 *
 * <p>手写而不用 Mockito：计划 01 把 Boot 4 的 test starter 拆开引入，
 * Mockito 是否在 classpath 上没有验证过。
 */
class StubCommandRunner implements CommandRunner {

    static final String DEFAULT_PROBE_JSON = """
            {
              "streams": [
                {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080},
                {"codec_type": "audio", "codec_name": "aac"}
              ],
              "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                         "duration": "600.000000", "bit_rate": "2119721"}
            }
            """;

    private final List<List<String>> invocations = new ArrayList<>();

    private volatile String probeJson = DEFAULT_PROBE_JSON;
    private volatile int outputWidth = 1600;
    private volatile int outputHeight = 900;
    private volatile int exitCode = 0;

    void respondToProbeWith(String json) {
        this.probeJson = json;
    }

    void produceImageOfSize(int width, int height) {
        this.outputWidth = width;
        this.outputHeight = height;
    }

    void failWith(int exitCode) {
        this.exitCode = exitCode;
    }

    List<List<String>> invocations() {
        return List.copyOf(invocations);
    }

    boolean ranCommandContaining(String fragment) {
        return invocations.stream().anyMatch(command -> command.stream().anyMatch(
                argument -> argument.contains(fragment)));
    }

    @Override
    public CommandResult run(List<String> command, Duration timeout) throws IOException {
        invocations.add(List.copyOf(command));

        if (exitCode != 0) {
            return new CommandResult(exitCode, "", "stub failure");
        }
        if (command.contains("-print_format")) {
            return new CommandResult(0, probeJson, "");
        }

        Path output = Path.of(command.get(command.size() - 1));
        Files.createDirectories(output.getParent());
        writeJpeg(output, outputWidth, outputHeight);
        return new CommandResult(0, "", "frame= 1 stub");
    }

    private static void writeJpeg(Path output, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        if (!ImageIO.write(image, "jpg", output.toFile())) {
            throw new IOException("当前 JDK 没有 JPEG 编码器，测试无法继续");
        }
    }
}
```

- [ ] **Step 2: 写会失败的集成测试**

`src/test/java/com/mymedia/preview/VideoPreviewJobTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(VideoPreviewJobTest.StubRunnerConfig.class)
@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class VideoPreviewJobTest extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRunnerConfig {

        @Bean
        @Primary
        StubCommandRunner stubCommandRunner() {
            return new StubCommandRunner();
        }
    }

    @TempDir
    Path root;

    @Autowired
    StubCommandRunner runner;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    PreviewTrigger previewTrigger;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    @BeforeEach
    void scanOneMovie() throws IOException {
        Path movie = root.resolve("电影/沙漠风暴 (2019).mp4");
        Files.createDirectories(movie.getParent());
        Files.write(movie, new byte[4096]);

        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
    }

    private VideoFile onlyFile() {
        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        return catalogService.filesOf(items.get(0).getId()).get(0);
    }

    @Test
    void writesProbeResultBackIntoVideoFile() {
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT duration_seconds, width, height, video_codec, audio_codec, bitrate, container,"
                        + " probe_raw::text AS raw FROM video_file WHERE id = ?", file.getId());
        assertThat(row.get("duration_seconds")).isEqualTo(600);
        assertThat(row.get("width")).isEqualTo(1920);
        assertThat(row.get("height")).isEqualTo(1080);
        assertThat(row.get("video_codec")).isEqualTo("h264");
        assertThat(row.get("audio_codec")).isEqualTo("aac");
        assertThat(row.get("bitrate")).isEqualTo(2119721L);
        assertThat(row.get("container")).isEqualTo("mov");
        assertThat((String) row.get("raw")).contains("h264");
    }

    @Test
    void generatesCoverAndThumbnailOnDisk() throws IOException {
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow();
        DerivedAsset thumbnail = assetService
                .find(DerivedAssetKind.THUMBNAIL, file.getScannedFileId()).orElseThrow();

        assertThat(Files.size(assetService.pathOf(cover))).isPositive();
        assertThat(Files.size(assetService.pathOf(thumbnail))).isPositive();
        assertThat(cover.getWidth()).isEqualTo(1600);
        // 缩略图从封面再缩，不再解一次视频
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(180);
    }

    @Test
    void seeksToTenPercentOfDurationWhenExtractingTheFrame() {
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        // 600 秒 → 60.000
        assertThat(runner.ranCommandContaining("60.000")).isTrue();
    }

    @Test
    void assignsCoverToTheOwningItem() {
        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        VideoFile file = catalogService.filesOf(item.getId()).get(0);

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        Long assetId = jdbc.queryForObject(
                "SELECT cover_asset_id FROM video_item WHERE id = ?", Long.class, item.getId());
        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow();
        assertThat(assetId).isEqualTo(cover.getId());
    }

    @Test
    void reRunningIsIdempotentAndDoesNotDuplicateAssets() {
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();
        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ? AND kind = 'COVER'",
                Integer.class, file.getScannedFileId())).isEqualTo(1);
    }

    @Test
    void enqueuesSpriteGenerationForLongEnoughVideos() {
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'SPRITE_GENERATE' AND payload->>'targetId' = ?",
                Integer.class, String.valueOf(file.getId()))).isEqualTo(1);
    }

    @Test
    void skipsSpriteForVeryShortClips() {
        runner.respondToProbeWith("""
                {"streams": [{"codec_type": "video", "codec_name": "h264", "width": 320, "height": 240}],
                 "format": {"format_name": "matroska,webm", "duration": "4.000000"}}
                """);
        VideoFile file = onlyFile();

        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        // 4 秒的片子做 100 帧雪碧图没有意义
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'SPRITE_GENERATE'", Integer.class)).isZero();
    }

    @Test
    void probeFailureFailsTheJobSoItCanBeRetried() {
        runner.failWith(1);
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();

        // 探测失败通常意味着文件当下读不到（盘掉了、正在写入），值得重试
        String status = jdbc.queryForObject(
                "SELECT status FROM job WHERE id = ?", String.class, jobId);
        assertThat(status).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM job WHERE id = ?", Integer.class, jobId))
                .isEqualTo(1);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoPreviewJobTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`PreviewTrigger` 不存在。

- [ ] **Step 4: 给 `video` 模块加写回入口**

`src/main/java/com/mymedia/video/VideoProbeData.java`：

```java
package com.mymedia.video;

/**
 * 一次媒体探测的结果，由 {@code preview} 模块填好后交回本模块写入。
 *
 * <p>每个字段都可能为 {@code null}：容器格式五花八门，缺时长、缺比特率、
 * 纯音频轨都属正常。
 */
public record VideoProbeData(
        Integer durationSeconds,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        Long bitrate,
        String container,
        String rawJson) {
}
```

`src/main/java/com/mymedia/video/VideoProbeStore.java`：

```java
package com.mymedia.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 探测结果的写入。
 *
 * <p>走 {@link JdbcTemplate} 而不是 JPA，理由和计划 03、04 一致：
 * {@code probe_raw} 是 jsonb 列，本项目一律不做 JPA 映射
 * （{@code ddl-auto=validate} 对 Hibernate 的 JSON 类型映射很挑）。
 * 顺带一条 UPDATE 就写完 8 个字段，不用先把实体读出来。
 */
@Component
class VideoProbeStore {

    private final JdbcTemplate jdbc;

    VideoProbeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void apply(Long videoFileId, VideoProbeData probe) {
        jdbc.update("""
                UPDATE video_file
                   SET duration_seconds = ?, width = ?, height = ?,
                       video_codec = ?, audio_codec = ?, bitrate = ?, container = ?,
                       probe_raw = CAST(? AS jsonb)
                 WHERE id = ?
                """,
                probe.durationSeconds(), probe.width(), probe.height(),
                truncate(probe.videoCodec(), 32), truncate(probe.audioCodec(), 32),
                probe.bitrate(), truncate(probe.container(), 16),
                probe.rawJson(), videoFileId);
    }

    /**
     * 编解码器与容器列都是有长度上限的 VARCHAR。ffprobe 偶尔会给出很长的
     * 组合名，截断比让整个任务因为一列超长而失败要好。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
```

在 `src/main/java/com/mymedia/video/VideoCatalogService.java` 中：

1. 构造器新增两个依赖 `VideoProbeStore probeStore` 与 `org.springframework.jdbc.core.JdbcTemplate jdbc`，并赋给同名字段。
2. 追加以下三个方法（放在既有查询方法之后）：

```java
    /** 由 {@code preview} 模块在探测完成后调用，把技术参数写回语义层。 */
    @Transactional
    public void applyProbe(Long videoFileId, VideoProbeData probe) {
        probeStore.apply(videoFileId, probe);
    }

    /**
     * 条目还没有封面时才写入，返回是否真的写了。
     *
     * <p>"判断没有"和"写入"必须是同一个原子操作：一个剧集条目下的多个文件会
     * 各自跑一次预览生成，若先查后写，两个并发任务会互相覆盖封面。
     */
    @Transactional
    public boolean assignCoverIfAbsent(Long itemId, Long assetId) {
        return jdbc.update(
                "UPDATE video_item SET cover_asset_id = ? WHERE id = ? AND cover_asset_id IS NULL",
                assetId, itemId) > 0;
    }

    /** 扫描完成后的封面补齐用：列出该库中还没有封面的条目。 */
    @Transactional(readOnly = true)
    public List<Long> itemsWithoutCover(Long libraryId, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM video_item WHERE library_id = ? AND cover_asset_id IS NULL"
                        + " ORDER BY id LIMIT ?",
                Long.class, libraryId, limit);
    }
```

- [ ] **Step 5: 写来源定位与图片缩放**

`src/main/java/com/mymedia/preview/SourceFileLocator.java`：

```java
package com.mymedia.preview;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 物理文件 id → 磁盘绝对路径。
 *
 * <p>返回空表示"这个文件现在拿不到"——扫描把消失的文件标成 {@code MISSING}
 * 而不删除（外接盘没挂载是常态），预览生成遇到这种情况应当安静跳过，
 * 而不是抛异常让任务反复重试。
 */
@Component
class SourceFileLocator {

    private static final Logger log = LoggerFactory.getLogger(SourceFileLocator.class);

    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;

    SourceFileLocator(ScannedFileQueryService scannedFiles, LibraryService libraryService) {
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
    }

    Optional<Path> locate(Long scannedFileId) {
        ScannedFile file = scannedFiles.getById(scannedFileId);
        if (file.getStatus() != ScannedFileStatus.ACTIVE) {
            log.debug("跳过预览生成：文件已标记 MISSING，scannedFileId={}", scannedFileId);
            return Optional.empty();
        }
        Path path = Path.of(libraryService.getById(file.getLibraryId()).getRootPath())
                .resolve(file.getRelativePath());
        if (!Files.isReadable(path)) {
            log.debug("跳过预览生成：文件不可读 {}", path);
            return Optional.empty();
        }
        return Optional.of(path);
    }
}
```

`src/main/java/com/mymedia/preview/ImageScaler.java`：

```java
package com.mymedia.preview;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 等比缩放并写出 JPEG。
 *
 * <p>用 JDK 自带的 {@code javax.imageio}，不引 Thumbnailator / imgscalr——
 * 本项目只需要"等比缩到指定宽度"这一个操作，为它加一个依赖说不出理由。
 *
 * <p>Spring Boot 默认设置 {@code java.awt.headless=true}，
 * {@link BufferedImage} 与 {@link Graphics2D} 在无显示环境下工作正常。
 */
final class ImageScaler {

    /** 缩放结果的实际尺寸。 */
    record Size(int width, int height) {
    }

    private ImageScaler() {
    }

    static BufferedImage read(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("无法识别的图片格式: " + source);
            }
            return image;
        }
    }

    static BufferedImage read(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("无法识别的图片格式");
        }
        return image;
    }

    /**
     * 等比缩到目标宽度并写成 JPEG，返回实际尺寸。
     *
     * <p>源图比目标还窄时不放大——放大只会让文件变大、观感变糊。
     */
    static Size writeJpeg(BufferedImage source, int targetWidth, Path output) throws IOException {
        int width = Math.min(targetWidth, source.getWidth());
        int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));

        // TYPE_INT_RGB：JPEG 没有 alpha 通道，带透明度的源图直接写会得到偏色的结果
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();

        Files.createDirectories(output.getParent());
        if (!ImageIO.write(scaled, "jpg", output.toFile())) {
            throw new IOException("当前运行时没有 JPEG 编码器");
        }
        return new Size(width, height);
    }
}
```

- [ ] **Step 6: 写任务载荷与排队入口**

`src/main/java/com/mymedia/preview/PreviewTarget.java`：

```java
package com.mymedia.preview;

/** {@code PREVIEW_GENERATE} 的目标种类。 */
enum PreviewTarget {

    /** 目标是一个 {@code video_file} 行。 */
    VIDEO_FILE,

    /** 目标是一个 {@code image_node} 行。 */
    IMAGE_NODE
}
```

`src/main/java/com/mymedia/preview/PreviewTrigger.java`：

```java
package com.mymedia.preview;

import com.mymedia.jobs.JobQueue;
import org.springframework.stereotype.Service;

/**
 * 预览生成的排队入口，是 {@code preview} 模块对外的写入 API。
 *
 * <p>全部走 {@code dedup_key}：同一个目标反复排队只会得到同一个待办任务。
 * 事件监听器与扫描完成后的补齐逻辑可以放心地重复调用。
 */
@Service
public class PreviewTrigger {

    private final JobQueue jobQueue;

    PreviewTrigger(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    public Long requestVideoPreview(Long videoFileId) {
        return enqueue(PreviewJobHandler.JOB_TYPE, PreviewTarget.VIDEO_FILE, videoFileId);
    }

    public Long requestImagePreview(Long imageNodeId) {
        return enqueue(PreviewJobHandler.JOB_TYPE, PreviewTarget.IMAGE_NODE, imageNodeId);
    }

    public Long requestSprite(Long videoFileId) {
        return enqueue(SpriteJobHandler.JOB_TYPE, PreviewTarget.VIDEO_FILE, videoFileId);
    }

    private Long enqueue(String jobType, PreviewTarget target, Long targetId) {
        String payload = "{\"target\":\"" + target.name() + "\",\"targetId\":" + targetId + "}";
        String dedupKey = jobType + ":" + target.name() + ":" + targetId;
        return jobQueue.enqueue(jobType, payload, dedupKey);
    }
}
```

> `SpriteJobHandler` 在 Task 5 才创建。**Task 3 先建一个只有常量的占位实现是不可接受的**，所以本步先把 `requestSprite` 里的 `SpriteJobHandler.JOB_TYPE` 写成字面量 `"SPRITE_GENERATE"`，Task 5 建好类之后再改成常量引用。Task 5 Step 6 会明确要求这次替换。

- [ ] **Step 7: 写视频预览生成器**

`src/main/java/com/mymedia/preview/VideoPreviewGenerator.java`：

```java
package com.mymedia.preview;

import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoProbeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 一个视频文件的完整预览链：探测 → 回填 → 抽帧封面 → 缩略图 → 排队雪碧图。
 *
 * <p><b>探测必须先于抽帧</b>：封面取时长的 10% 处，不知道时长就选不出抽帧点。
 * 两者放在同一个处理器里顺序执行，而不是拆成两个任务——spec 的 job type 清单里
 * 本来也没有单独的 PROBE 类型。
 *
 * <p>失败语义分两档：
 * <ul>
 *   <li><b>探测失败 → 抛异常</b>。连容器都读不出来通常意味着文件当下不可读
 *       （盘掉了、正在写入），值得按退避重试。</li>
 *   <li><b>抽帧失败 → 记警告后返回</b>。文件可读却抽不出帧是内容问题，
 *       重试多少次都一样，让任务成功结束、条目暂时没有封面即可。</li>
 * </ul>
 */
@Component
class VideoPreviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(VideoPreviewGenerator.class);

    private final CommandRunner commandRunner;
    private final PreviewProperties properties;
    private final SourceFileLocator locator;
    private final DerivedAssetService assets;
    private final VideoCatalogService catalog;
    private final PreviewTrigger trigger;

    VideoPreviewGenerator(CommandRunner commandRunner,
                          PreviewProperties properties,
                          SourceFileLocator locator,
                          DerivedAssetService assets,
                          VideoCatalogService catalog,
                          PreviewTrigger trigger) {
        this.commandRunner = commandRunner;
        this.properties = properties;
        this.locator = locator;
        this.assets = assets;
        this.catalog = catalog;
        this.trigger = trigger;
    }

    void generate(Long videoFileId) throws IOException, InterruptedException {
        VideoFile file = catalog.getFile(videoFileId);
        Optional<Path> source = locator.locate(file.getScannedFileId());
        if (source.isEmpty()) {
            return;
        }
        Path input = source.get();

        FfprobeOutput probe = probe(input);
        catalog.applyProbe(videoFileId, new VideoProbeData(
                probe.durationSeconds(), probe.width(), probe.height(),
                probe.videoCodec(), probe.audioCodec(), probe.bitrate(),
                probe.container(), probe.rawJson()));

        Optional<Path> cover = extractCover(input, file.getScannedFileId(), probe.durationSeconds());
        if (cover.isPresent()) {
            writeAssets(file, cover.get());
        }

        Integer duration = probe.durationSeconds();
        if (duration != null && duration >= properties.spriteMinDurationSeconds()) {
            trigger.requestSprite(videoFileId);
        } else {
            log.debug("跳过雪碧图：时长 {} 秒不足 {} 秒，videoFileId={}",
                    duration, properties.spriteMinDurationSeconds(), videoFileId);
        }
    }

    private FfprobeOutput probe(Path input) throws IOException, InterruptedException {
        CommandResult result = commandRunner.run(
                MediaCommands.probe(properties.ffprobePath(), input), properties.commandTimeout());
        if (!result.succeeded()) {
            throw new IOException("ffprobe 探测失败（exit=" + result.exitCode() + "）: "
                    + input + " —— " + result.stderr());
        }
        return FfprobeParser.parse(result.stdout());
    }

    private Optional<Path> extractCover(Path input, Long sourceFileId, Integer durationSeconds)
            throws IOException, InterruptedException {

        Path output = assets.prepare(DerivedAssetKind.COVER, sourceFileId);
        CommandResult result = commandRunner.run(
                MediaCommands.coverFrame(properties.ffmpegPath(), input, durationSeconds,
                        properties.coverWidth(), output),
                properties.commandTimeout());

        if (!result.succeeded() || !Files.exists(output) || Files.size(output) == 0) {
            log.warn("抽帧失败，条目暂时没有封面: {} —— {}", input, result.stderr());
            return Optional.empty();
        }
        return Optional.of(output);
    }

    /** 封面已经在磁盘上，缩略图从它再缩一次——不再解一次视频。 */
    private void writeAssets(VideoFile file, Path coverPath) throws IOException {
        BufferedImage coverImage = ImageScaler.read(coverPath);
        DerivedAsset cover = assets.record(DerivedAssetKind.COVER, file.getScannedFileId(),
                coverImage.getWidth(), coverImage.getHeight());

        Path thumbnailPath = assets.prepare(DerivedAssetKind.THUMBNAIL, file.getScannedFileId());
        ImageScaler.Size size = ImageScaler.writeJpeg(
                coverImage, properties.thumbnailWidth(), thumbnailPath);
        assets.record(DerivedAssetKind.THUMBNAIL, file.getScannedFileId(),
                size.width(), size.height());

        boolean assigned = catalog.assignCoverIfAbsent(file.getItemId(), cover.getId());
        log.debug("封面生成完毕 videoFileId={} assetId={} 设为条目封面={}",
                file.getId(), cover.getId(), assigned);
    }
}
```

- [ ] **Step 8: 写任务处理器**

`src/main/java/com/mymedia/preview/PreviewJobHandler.java`：

```java
package com.mymedia.preview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;

/**
 * {@code PREVIEW_GENERATE} 的处理器。
 *
 * <p>两个域共用一个任务类型、按载荷里的 {@code target} 分派，而不是各建一个类型。
 * 理由：任务队列关心的是"有多少预览待生成"，不是"它是视频还是图片"；
 * 分两个类型会让运维视角多一层无意义的切分。
 */
@Component
class PreviewJobHandler implements JobHandler {

    static final String JOB_TYPE = "PREVIEW_GENERATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VideoPreviewGenerator videoGenerator;

    PreviewJobHandler(VideoPreviewGenerator videoGenerator) {
        this.videoGenerator = videoGenerator;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = MAPPER.readTree(job.getPayload());
        PreviewTarget target = PreviewTarget.valueOf(payload.path("target").asString());
        Long targetId = payload.path("targetId").asLong();

        switch (target) {
            case VIDEO_FILE -> videoGenerator.generate(targetId);
            case IMAGE_NODE -> throw new UnsupportedOperationException(
                    "图片预览在 Task 4 实现");
        }
    }
}
```

> `IMAGE_NODE` 分支在 Task 4 补全。**这不是占位符**——现在排不出 `IMAGE_NODE` 载荷的任务（只有 `PreviewTrigger.requestImagePreview` 能排，而它的调用方要到 Task 4 才出现），抛异常比返回成功更诚实。

- [ ] **Step 9: 扩大 `preview` 的允许依赖**

修改 `src/main/java/com/mymedia/preview/package-info.java` 的 `allowedDependencies`：

```java
        allowedDependencies = {"shared", "library", "jobs", "scan", "video"})
```

- [ ] **Step 10: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='VideoPreviewJobTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`VideoPreviewJobTest` 8 个用例通过，`ModularityTests` 通过。

- [ ] **Step 11: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/preview src/main/java/com/mymedia/video src/test/java/com/mymedia/preview
git commit -m "feat: 实现视频预览生成（探测回填 + 抽帧封面 + 缩略图）

探测与抽帧在同一个处理器里顺序执行：封面取时长的 10%，
不知道时长就选不出抽帧点。

缩略图从封面再缩，不第二次解视频。
assignCoverIfAbsent 用一条带 WHERE 的 UPDATE 表达'没有才写'，
避免同一条目的多个文件并发覆盖封面。"
```

---

## Task 4: 图片预览生成（图集与漫画封面）

**Files:**
- Modify: `src/main/java/com/mymedia/image/ImageCatalogService.java`（新增 3 个方法与 3 个依赖）
- Create: `src/main/java/com/mymedia/preview/ImagePreviewGenerator.java`
- Modify: `src/main/java/com/mymedia/preview/PreviewJobHandler.java`（补全 `IMAGE_NODE` 分支）
- Modify: `src/main/java/com/mymedia/preview/package-info.java`（`allowedDependencies` 增补 `image`）
- Test: `src/test/java/com/mymedia/preview/ImagePreviewJobTest.java`

**Interfaces:**
- Consumes: `ImageCatalogService`、`ImageNode`、`ImageFile`（计划 04 Task 1、3）、`ImageArchiveReader`（计划 04 Task 4）、`ScannedFileQueryService`（计划 02）、`LibraryService`（计划 01）、`DerivedAssetService`、`ImageScaler`、`PreviewJobHandler`（Task 1、3）
- Produces:
  - `ImageCatalogService` 新增：
    - `public InputStream openPageForProcessing(Long imageFileId) throws IOException` — **不做权限校验**
    - `public boolean assignCoverIfAbsent(Long nodeId, Long assetId)`
    - `public List<Long> nodesWithoutCover(Long libraryId, int limit)`
  - `class ImagePreviewGenerator`（package-private，Spring bean）— `void generate(Long nodeId) throws IOException`

### 封面的来源文件是「首页」，不是节点

`derived_asset.source_scanned_file_id` 是单一外键、没有多态，所以图片节点的封面必须挂在**某个具体的物理文件**上。取该节点的第一页：

- 散图目录 → 第一张图片自己的 `scanned_file`
- CBZ → 压缩包本体的 `scanned_file`（一个压缩包只有一个封面，正好对上 `UNIQUE (source, kind)`）

节点没有直属页（纯中间目录）时不生成封面，直接返回。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/preview/ImagePreviewJobTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class ImagePreviewJobTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    PreviewTrigger previewTrigger;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    private static byte[] pngBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var buffer = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private void writeLooseImage(String relative, int width, int height) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, pngBytes(width, height, Color.BLUE));
    }

    private void writeArchive(String relative, String... entries) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(pngBytes(800, 1200, Color.RED));
                zip.closeEntry();
            }
        }
    }

    private void scanLibrary() {
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        // 扫描会再排出 ARCHIVE_INDEX，需要第二轮
        jobPoller.pollOnce();
    }

    private ImageNode nodeNamed(String name) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND name = ?",
                Long.class, library.getId(), name);
        return catalogService.getNode(id);
    }

    /** 压缩包叶子节点的命名规则归计划 04 管，这里按 source_kind 找而不是猜名字。 */
    private ImageNode onlyArchiveNode() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND source_kind = 'ARCHIVE'",
                Long.class, library.getId());
        return catalogService.getNode(id);
    }

    @Test
    void makesCoverFromFirstPageOfALooseImageFolder() throws IOException {
        writeLooseImage("画师A/002.png", 900, 1400);
        writeLooseImage("画师A/001.png", 1000, 1500);
        scanLibrary();

        ImageNode node = nodeNamed("画师A");
        previewTrigger.requestImagePreview(node.getId());
        jobPoller.pollOnce();

        List<ImageFile> pages = catalogService.pagesOf(node.getId());
        // 自然序的第一页是 001.png，封面必须来自它而不是先被扫到的 002.png
        Long firstPageSource = pages.get(0).getScannedFileId();
        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, firstPageSource).orElseThrow();

        assertThat(Files.size(assetService.pathOf(cover))).isPositive();
        assertThat(cover.getWidth()).isEqualTo(640);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isEqualTo(cover.getId());
    }

    @Test
    void makesCoverFromFirstEntryInsideAnArchiveWithoutExtractingIt() throws IOException {
        writeArchive("漫画/某作品 第01卷.cbz", "002.png", "001.png");
        scanLibrary();

        ImageNode node = onlyArchiveNode();
        previewTrigger.requestImagePreview(node.getId());
        jobPoller.pollOnce();

        ImageFile firstPage = catalogService.pagesOf(node.getId()).get(0);
        assertThat(firstPage.getArchiveEntryName()).isEqualTo("001.png");

        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, firstPage.getScannedFileId()).orElseThrow();
        assertThat(cover.getWidth()).isEqualTo(640);
        assertThat(cover.getHeight()).isEqualTo(960);
        // 绝不解压到磁盘：这个来源文件在派生目录里只应有封面与缩略图两个产物
        try (var files = Files.walk(Path.of("target/test-derived"))) {
            assertThat(files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .startsWith(firstPage.getScannedFileId() + "-"))
                    .count()).isEqualTo(2);
        }
    }

    @Test
    void alsoWritesAThumbnail() throws IOException {
        writeLooseImage("图集/a.png", 1000, 1000);
        scanLibrary();

        ImageNode node = nodeNamed("图集");
        previewTrigger.requestImagePreview(node.getId());
        jobPoller.pollOnce();

        Long sourceId = catalogService.pagesOf(node.getId()).get(0).getScannedFileId();
        DerivedAsset thumbnail = assetService
                .find(DerivedAssetKind.THUMBNAIL, sourceId).orElseThrow();
        assertThat(thumbnail.getWidth()).isEqualTo(320);
    }

    @Test
    void nodeWithoutOwnPagesGetsNoCoverAndNoFailure() throws IOException {
        writeLooseImage("顶层/子目录/a.png", 500, 500);
        scanLibrary();

        ImageNode parent = nodeNamed("顶层");
        Long jobId = previewTrigger.requestImagePreview(parent.getId());
        jobPoller.pollOnce();

        // 纯中间目录没有直属页，任务应当安静成功
        assertThat(jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, parent.getId())).isNull();
    }

    @Test
    void doesNotUpscaleAPageSmallerThanTheCoverWidth() throws IOException {
        writeLooseImage("小图/a.png", 200, 300);
        scanLibrary();

        ImageNode node = nodeNamed("小图");
        previewTrigger.requestImagePreview(node.getId());
        jobPoller.pollOnce();

        Long sourceId = catalogService.pagesOf(node.getId()).get(0).getScannedFileId();
        DerivedAsset cover = assetService.find(DerivedAssetKind.COVER, sourceId).orElseThrow();
        // 放大只会让文件更大、观感更糊
        assertThat(cover.getWidth()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImagePreviewJobTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol|UnsupportedOperation" t.log | head -10
```

Expected: 失败——`ImageCatalogService.openPageForProcessing` 不存在（编译错误），或处理器仍抛 `UnsupportedOperationException`。

- [ ] **Step 3: 给 `image` 模块加写回与取页入口**

在 `src/main/java/com/mymedia/image/ImageCatalogService.java` 中：

1. 构造器新增依赖 `ImageArchiveReader archiveReader`、`com.mymedia.scan.ScannedFileQueryService scannedFiles`、`com.mymedia.library.LibraryService libraryService`、`org.springframework.jdbc.core.JdbcTemplate jdbc`，并赋给同名字段。
2. 追加以下三个方法：

```java
    /**
     * 打开一页的字节流，<b>不做任何权限校验</b>。
     *
     * <p><b>仅供后台任务使用</b>（封面生成、将来的尺寸探测）。这些任务没有
     * 调用者身份可言——它们由扫描触发、在 worker 线程上运行。面向用户的入口
     * 是 {@code ImagePageService.locate(userId, fileId)}，那条路径会校验访问权
     * 并对无权用户返回 404。
     *
     * <p><b>不要把本方法接到任何 controller 上。</b>
     */
    @Transactional(readOnly = true)
    public InputStream openPageForProcessing(Long imageFileId) throws IOException {
        ImageFile file = getFile(imageFileId);
        ImageNode node = getNode(file.getNodeId());
        Path path = Path.of(libraryService.getById(node.getLibraryId()).getRootPath())
                .resolve(scannedFiles.getById(file.getScannedFileId()).getRelativePath());
        return file.getArchiveEntryName() == null
                ? Files.newInputStream(path)
                : archiveReader.openEntry(path, file.getArchiveEntryName());
    }

    /**
     * 节点还没有封面时才写入，返回是否真的写了。
     *
     * <p>与视频域同款：判断与写入必须是同一条 UPDATE，否则并发的补齐任务会互相覆盖。
     */
    @Transactional
    public boolean assignCoverIfAbsent(Long nodeId, Long assetId) {
        return jdbc.update(
                "UPDATE image_node SET cover_asset_id = ? WHERE id = ? AND cover_asset_id IS NULL",
                assetId, nodeId) > 0;
    }

    /** 扫描完成后的封面补齐用：列出该库中有直属页却还没有封面的节点。 */
    @Transactional(readOnly = true)
    public List<Long> nodesWithoutCover(Long libraryId, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM image_node
                 WHERE library_id = ? AND cover_asset_id IS NULL
                   AND direct_page_count > 0 AND status = 'ACTIVE'
                 ORDER BY id LIMIT ?
                """, Long.class, libraryId, limit);
    }
```

需要新增的 import：`java.io.IOException`、`java.io.InputStream`、`java.nio.file.Files`、`java.nio.file.Path`、`java.util.List`。

- [ ] **Step 4: 写图片预览生成器**

`src/main/java/com/mymedia/preview/ImagePreviewGenerator.java`：

```java
package com.mymedia.preview;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 图片节点的封面与缩略图。
 *
 * <p>来源是该节点<b>自然序的第一页</b>：散图目录取第一张图片本身的 scanned_file，
 * CBZ 取压缩包本体的 scanned_file。因此 {@code derived_asset} 依旧是
 * 「一个来源文件 + 一种资源」的单一外键模型，不需要多态。
 *
 * <p>压缩包内的页走 {@code ZipFile.getInputStream} 按需读取，
 * <b>绝不解压到磁盘</b>——这条约束在计划 04 已经定死，这里只是继续遵守。
 */
@Component
class ImagePreviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImagePreviewGenerator.class);

    private final ImageCatalogService catalog;
    private final DerivedAssetService assets;
    private final PreviewProperties properties;

    ImagePreviewGenerator(ImageCatalogService catalog,
                          DerivedAssetService assets,
                          PreviewProperties properties) {
        this.catalog = catalog;
        this.assets = assets;
        this.properties = properties;
    }

    void generate(Long nodeId) throws IOException {
        List<ImageFile> pages = catalog.pagesOf(nodeId);
        if (pages.isEmpty()) {
            // 纯中间目录没有直属页，它的封面由界面用子节点的封面代偿，这里不是错误
            log.debug("节点没有直属页，跳过封面生成 nodeId={}", nodeId);
            return;
        }

        ImageFile firstPage = pages.get(0);
        BufferedImage source;
        try (InputStream in = catalog.openPageForProcessing(firstPage.getId())) {
            source = ImageScaler.read(in);
        }

        Path coverPath = assets.prepare(DerivedAssetKind.COVER, firstPage.getScannedFileId());
        ImageScaler.Size coverSize = ImageScaler.writeJpeg(
                source, properties.coverWidth(), coverPath);
        DerivedAsset cover = assets.record(DerivedAssetKind.COVER,
                firstPage.getScannedFileId(), coverSize.width(), coverSize.height());

        Path thumbnailPath = assets.prepare(DerivedAssetKind.THUMBNAIL, firstPage.getScannedFileId());
        ImageScaler.Size thumbnailSize = ImageScaler.writeJpeg(
                source, properties.thumbnailWidth(), thumbnailPath);
        assets.record(DerivedAssetKind.THUMBNAIL, firstPage.getScannedFileId(),
                thumbnailSize.width(), thumbnailSize.height());

        boolean assigned = catalog.assignCoverIfAbsent(nodeId, cover.getId());
        log.debug("图片封面生成完毕 nodeId={} assetId={} 设为节点封面={}",
                nodeId, cover.getId(), assigned);
    }
}
```

- [ ] **Step 5: 补全处理器的图片分支**

修改 `src/main/java/com/mymedia/preview/PreviewJobHandler.java`：构造器增加 `ImagePreviewGenerator imageGenerator` 依赖，并把 switch 改成：

```java
        switch (target) {
            case VIDEO_FILE -> videoGenerator.generate(targetId);
            case IMAGE_NODE -> imageGenerator.generate(targetId);
        }
```

- [ ] **Step 6: 扩大 `preview` 的允许依赖**

修改 `src/main/java/com/mymedia/preview/package-info.java`：

```java
        allowedDependencies = {"shared", "library", "jobs", "scan", "video", "image"})
```

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ImagePreviewJobTest,VideoPreviewJobTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`ImagePreviewJobTest` 5 个用例通过，其余保持通过。

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/preview src/main/java/com/mymedia/image src/test/java/com/mymedia/preview
git commit -m "feat: 实现图片域封面生成（含 CBZ 首页直读）

封面来源是节点自然序的第一页，因此 derived_asset 依旧是
'一个来源文件 + 一种资源'的单一外键模型。

openPageForProcessing 明确不做权限校验，javadoc 写清它只供
后台任务使用——面向用户的入口仍是会校验访问权的 ImagePageService。"
```

---

## Task 5: 雪碧图与 WebVTT

**Files:**
- Create: `src/main/java/com/mymedia/preview/WebVttWriter.java`
- Create: `src/main/java/com/mymedia/preview/SpriteJobHandler.java`
- Modify: `src/main/java/com/mymedia/preview/PreviewTrigger.java`（把字面量换成常量引用）
- Test: `src/test/java/com/mymedia/preview/WebVttWriterTest.java`
- Test: `src/test/java/com/mymedia/preview/SpriteJobTest.java`

**Interfaces:**
- Consumes: `CommandRunner`、`MediaCommands`、`PreviewProperties`、`DerivedAssetService`、`SourceFileLocator`、`ImageScaler`（Task 1–3）、`VideoCatalogService`、`VideoFile`（计划 03）、`JobHandler`、`Job`（计划 01）
- Produces:
  - `class WebVttWriter`（package-private）— `static String write(String imageUrl, int frameCount, int columns, int tileWidth, int tileHeight, double totalSeconds)`
  - `class SpriteJobHandler implements JobHandler`（package-private）— `static final String JOB_TYPE = "SPRITE_GENERATE"`

### 为什么固定 100 帧、单张 10 × 10

进度条悬停预览要的是"大致看清这一段在放什么"，不是逐秒精度。把帧数与网格都固定下来之后：

- **永远只有一张图、一个 VTT**，省掉多图分页、跨图边界、按时长决定图数这一整套复杂度。
- 抽帧间隔由 `fps = 100 / 时长` 反推，10 分钟的片子每 6 秒一帧，2 小时的片子每 72 秒一帧，两端都能接受。
- 单图尺寸可控：160 宽 × 10 列 = 1600 像素宽，任何浏览器都吃得下。

### 图块尺寸从生成结果读，不靠计算

`scale=160:-2` 里的 `-2` 让 ffmpeg 自己算高度并取到偶数，**结果取决于源视频的宽高比与取整**，在 Java 里重算一遍必然对不上。正确做法是把生成好的雪碧图用 `ImageIO` 读一次，拿实际宽高除以行列数——多一次读图换来 VTT 坐标一定正确。

- [ ] **Step 1: 写会失败的 VTT 单元测试**

`src/test/java/com/mymedia/preview/WebVttWriterTest.java`：

```java
package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebVttWriterTest {

    @Test
    void writesOneCuePerFrameInRowMajorOrder() {
        String vtt = WebVttWriter.write("/api/assets/7", 4, 2, 100, 50, 8.0);

        assertThat(vtt).isEqualTo("""
                WEBVTT

                00:00:00.000 --> 00:00:02.000
                /api/assets/7#xywh=0,0,100,50

                00:00:02.000 --> 00:00:04.000
                /api/assets/7#xywh=100,0,100,50

                00:00:04.000 --> 00:00:06.000
                /api/assets/7#xywh=0,50,100,50

                00:00:06.000 --> 00:00:08.000
                /api/assets/7#xywh=100,50,100,50
                """);
    }

    @Test
    void lastCueEndsExactlyAtTotalDuration() {
        // 100 帧除 3601 秒除不尽，末帧不能因为累加误差而超出或不足
        String vtt = WebVttWriter.write("/api/assets/1", 100, 10, 160, 90, 3601.0);

        assertThat(vtt).contains("--> 01:00:01.000");
        assertThat(vtt.lines().filter(line -> line.contains("-->")).count()).isEqualTo(100);
    }

    @Test
    void formatsTimestampsWithHoursMinutesSecondsMillis() {
        String vtt = WebVttWriter.write("/api/assets/1", 2, 2, 10, 10, 7200.0);

        assertThat(vtt).contains("00:00:00.000 --> 01:00:00.000");
        assertThat(vtt).contains("01:00:00.000 --> 02:00:00.000");
    }

    @Test
    void singleFrameStillProducesAValidFile() {
        String vtt = WebVttWriter.write("/api/assets/1", 1, 10, 160, 90, 5.0);

        assertThat(vtt).startsWith("WEBVTT\n\n");
        assertThat(vtt).contains("00:00:00.000 --> 00:00:05.000");
        assertThat(vtt).contains("#xywh=0,0,160,90");
    }
}
```

- [ ] **Step 2: 写会失败的集成测试**

`src/test/java/com/mymedia/preview/SpriteJobTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(SpriteJobTest.StubRunnerConfig.class)
@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class SpriteJobTest extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRunnerConfig {

        @Bean
        @Primary
        StubCommandRunner stubCommandRunner() {
            return new StubCommandRunner();
        }
    }

    @TempDir
    Path root;

    @Autowired
    StubCommandRunner runner;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    PreviewTrigger previewTrigger;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private VideoFile file;

    @BeforeEach
    void scanAndProbe() throws IOException {
        Path movie = root.resolve("沙漠风暴 (2019).mp4");
        Files.write(movie, new byte[4096]);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();

        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        file = catalogService.filesOf(item.getId()).get(0);

        // 雪碧图依赖已探测出的时长，先跑一次预览生成
        runner.produceImageOfSize(1600, 900);
        previewTrigger.requestVideoPreview(file.getId());
        jobPoller.pollOnce();
    }

    @Test
    void generatesExactlyOneSheetAndOneVtt() {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        assertThat(assetService.find(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId()))
                .isPresent();
        assertThat(assetService.find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()))
                .isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?"
                        + " AND kind LIKE 'SPRITE%'", Integer.class, file.getScannedFileId()))
                .isEqualTo(2);
    }

    @Test
    void derivesTileGeometryFromTheGeneratedSheetRatherThanRecomputingIt() throws IOException {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();
        String content = Files.readString(assetService.pathOf(vtt));

        // 1600 × 900 的图切成 10 × 10 → 每块 160 × 90
        assertThat(content).contains("#xywh=0,0,160,90");
        assertThat(content).contains("#xywh=1440,810,160,90");
    }

    @Test
    void vttPointsAtTheSheetAssetEndpoint() throws IOException {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        DerivedAsset sheet = assetService
                .find(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId()).orElseThrow();
        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();

        assertThat(Files.readString(assetService.pathOf(vtt)))
                .contains("/api/assets/" + sheet.getId() + "#xywh=");
    }

    @Test
    void cuesSpanTheWholeDuration() throws IOException {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();
        String content = Files.readString(assetService.pathOf(vtt));

        // 桩探测返回 600 秒
        assertThat(content).startsWith("WEBVTT");
        assertThat(content).contains("00:00:00.000 --> 00:00:06.000");
        assertThat(content).contains("--> 00:10:00.000");
    }

    @Test
    void usesFpsThatFitsOneHundredFramesIntoTheDuration() {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        // 100 帧 / 600 秒
        assertThat(runner.ranCommandContaining("fps=0.166667,scale=160:-2,tile=10x10")).isTrue();
    }

    @Test
    void rerunOverwritesInsteadOfAccumulating() {
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();
        previewTrigger.requestSprite(file.getId());
        jobPoller.pollOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?"
                        + " AND kind = 'SPRITE_SHEET'", Integer.class, file.getScannedFileId()))
                .isEqualTo(1);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='WebVttWriterTest,SpriteJobTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`WebVttWriter` 与 `SpriteJobHandler` 不存在。

- [ ] **Step 4: 实现 VTT 生成**

`src/main/java/com/mymedia/preview/WebVttWriter.java`：

```java
package com.mymedia.preview;

import java.util.Locale;

/**
 * 把雪碧图的几何关系写成 WebVTT。
 *
 * <p>播放器（原生 {@code <track kind="metadata">} 或自绘控制条）按当前悬停时间
 * 在 VTT 里查到对应的 cue，cue 的正文是一个带 {@code #xywh=} 媒体片段的 URL，
 * 指明该时间点对应雪碧图上的哪一块。这是 JW Player / Video.js 一系的既定约定，
 * 不是本项目发明的格式。
 *
 * <p><b>纯函数</b>：没有 I/O、没有依赖，因此它的测试是逐字符断言整段输出的单元测试。
 */
final class WebVttWriter {

    private WebVttWriter() {
    }

    /**
     * @param imageUrl    雪碧图的访问地址（{@code /api/assets/{id}}）
     * @param frameCount  帧数
     * @param columns     每行几块
     * @param tileWidth   单块宽（<b>从生成结果读出来的实际值</b>，不要重算）
     * @param tileHeight  单块高
     * @param totalSeconds 视频总时长
     */
    static String write(String imageUrl, int frameCount, int columns,
                        int tileWidth, int tileHeight, double totalSeconds) {

        StringBuilder vtt = new StringBuilder("WEBVTT\n");
        for (int frame = 0; frame < frameCount; frame++) {
            // 用「第 n 帧的边界 = 总时长 × n / 帧数」而不是累加步长，
            // 末帧才能正好落在总时长上，不会攒出浮点误差
            long startMillis = Math.round(totalSeconds * 1000 * frame / frameCount);
            long endMillis = Math.round(totalSeconds * 1000 * (frame + 1) / frameCount);
            int x = (frame % columns) * tileWidth;
            int y = (frame / columns) * tileHeight;

            vtt.append('\n')
               .append(timestamp(startMillis)).append(" --> ").append(timestamp(endMillis))
               .append('\n')
               .append(imageUrl).append("#xywh=")
               .append(x).append(',').append(y).append(',')
               .append(tileWidth).append(',').append(tileHeight)
               .append('\n');
        }
        return vtt.toString();
    }

    /** WebVTT 要求 {@code HH:MM:SS.mmm}。 */
    private static String timestamp(long millis) {
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1000 % 60;
        long remainder = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, remainder);
    }
}
```

- [ ] **Step 5: 实现雪碧图任务**

`src/main/java/com/mymedia/preview/SpriteJobHandler.java`：

```java
package com.mymedia.preview;

import tools.jackson.databind.ObjectMapper;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code SPRITE_GENERATE}：生成进度条悬停预览用的雪碧图与它的 WebVTT 索引。
 *
 * <p>帧数与网格都是固定的（默认 100 帧、10 × 10），于是永远只有一张图、一个 VTT。
 * 这一个决定省掉了多图分页、跨图边界、按时长决定图数的全部复杂度。
 *
 * <p><b>图块尺寸从生成结果读，不靠计算</b>：{@code scale=160:-2} 的高度由 ffmpeg
 * 按源视频宽高比取偶数得出，在 Java 里重算必然对不上。多读一次图换 VTT 坐标一定正确。
 */
@Component
class SpriteJobHandler implements JobHandler {

    static final String JOB_TYPE = "SPRITE_GENERATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SpriteJobHandler.class);

    private final VideoCatalogService catalog;
    private final SourceFileLocator locator;
    private final CommandRunner commandRunner;
    private final DerivedAssetService assets;
    private final PreviewProperties properties;

    SpriteJobHandler(VideoCatalogService catalog,
                     SourceFileLocator locator,
                     CommandRunner commandRunner,
                     DerivedAssetService assets,
                     PreviewProperties properties) {
        this.catalog = catalog;
        this.locator = locator;
        this.commandRunner = commandRunner;
        this.assets = assets;
        this.properties = properties;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        Long videoFileId = MAPPER.readTree(job.getPayload()).path("targetId").asLong();
        VideoFile file = catalog.getFile(videoFileId);

        Integer duration = file.getDurationSeconds();
        if (duration == null || duration < properties.spriteMinDurationSeconds()) {
            log.debug("跳过雪碧图：时长未知或过短 videoFileId={} duration={}", videoFileId, duration);
            return;
        }

        Optional<Path> source = locator.locate(file.getScannedFileId());
        if (source.isEmpty()) {
            return;
        }

        Path sheetPath = assets.prepare(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId());
        CommandResult result = commandRunner.run(
                MediaCommands.spriteSheet(properties.ffmpegPath(), source.get(), duration,
                        properties.spriteFrames(), properties.spriteColumns(),
                        properties.spriteTileWidth(), sheetPath),
                properties.commandTimeout());

        if (!result.succeeded() || !Files.exists(sheetPath) || Files.size(sheetPath) == 0) {
            log.warn("雪碧图生成失败，进度条将没有悬停预览: {} —— {}", source.get(), result.stderr());
            return;
        }

        BufferedImage sheet = ImageScaler.read(sheetPath);
        DerivedAsset sheetAsset = assets.record(DerivedAssetKind.SPRITE_SHEET,
                file.getScannedFileId(), sheet.getWidth(), sheet.getHeight());

        writeVtt(file, sheetAsset, sheet, duration);
    }

    private void writeVtt(VideoFile file, DerivedAsset sheetAsset, BufferedImage sheet,
                          int durationSeconds) throws IOException {

        int columns = properties.spriteColumns();
        int rows = (int) Math.ceil((double) properties.spriteFrames() / columns);
        int tileWidth = sheet.getWidth() / columns;
        int tileHeight = sheet.getHeight() / rows;

        String vtt = WebVttWriter.write("/api/assets/" + sheetAsset.getId(),
                properties.spriteFrames(), columns, tileWidth, tileHeight, durationSeconds);

        Path vttPath = assets.prepare(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId());
        Files.writeString(vttPath, vtt, StandardCharsets.UTF_8);
        assets.record(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId(), null, null);
    }
}
```

- [ ] **Step 6: 把 `PreviewTrigger` 里的字面量换成常量**

修改 `src/main/java/com/mymedia/preview/PreviewTrigger.java` 的 `requestSprite`：

```java
    public Long requestSprite(Long videoFileId) {
        return enqueue(SpriteJobHandler.JOB_TYPE, PreviewTarget.VIDEO_FILE, videoFileId);
    }
```

（Task 3 Step 6 里为了让代码能编译先写了字面量 `"SPRITE_GENERATE"`，现在类已经存在，换回常量引用。）

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='WebVttWriterTest,SpriteJobTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`WebVttWriterTest` 4 个用例、`SpriteJobTest` 6 个用例通过。

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/preview src/test/java/com/mymedia/preview
git commit -m "feat: 添加进度条雪碧图与 WebVTT 索引

固定 100 帧、单张 10x10：永远只有一张图一个 VTT，
省掉多图分页与跨图边界的全部复杂度。

图块尺寸从生成结果读而不是重算——scale=160:-2 的高度由 ffmpeg
按宽高比取偶数得出，Java 里算必然对不上。"
```

---

## Task 6: 事件接线、扫描后补齐与派生资源访问端点

**Files:**
- Create: `src/main/java/com/mymedia/scan/spi/package-info.java`（若不存在）
- Create: `src/main/java/com/mymedia/scan/event/package-info.java`（若不存在）
- Create: `src/main/java/com/mymedia/video/event/package-info.java`（若不存在）
- Create: `src/main/java/com/mymedia/image/event/package-info.java`（若不存在）
- Modify: `src/main/java/com/mymedia/video/package-info.java`（收紧 `allowedDependencies`）
- Modify: `src/main/java/com/mymedia/image/package-info.java`（收紧 `allowedDependencies`）
- Modify: `src/main/java/com/mymedia/preview/package-info.java`（增补事件命名接口）
- Create: `src/main/java/com/mymedia/preview/PreviewEventListener.java`
- Create: `src/main/java/com/mymedia/preview/PreviewBackfill.java`
- Create: `src/main/java/com/mymedia/preview/web/AssetController.java`
- Test: `src/test/java/com/mymedia/preview/AssetControllerTest.java`
- Test: `src/test/java/com/mymedia/preview/PreviewWiringTest.java`

**Interfaces:**
- Consumes: `VideoItemCreated`（计划 03 Task 5）、`ImageNodeCreated`（计划 04 Task 2）、`LibraryScanCompleted`（计划 02 Task 6）、`LibraryAccessService`、`UserQueryService`（计划 01 Task 6、8）、`ScannedFileQueryService`（计划 02）、`PreviewTrigger`、`DerivedAssetService`（Task 1、3）
- Produces:
  - `class PreviewEventListener`（package-private，Spring bean）
  - `class PreviewBackfill`（package-private，Spring bean）
  - `GET /api/assets/{id}` — 派生资源的字节内容，带访问控制与 ETag

### 命名接口：这是 `verify()` 能过的前提

Spring Modulith 把模块的**嵌套包视为内部实现**。`com.mymedia.scan.event.ScannedFileDiscovered` 对 `scan` 之外的模块默认是不可见的，跨模块引用会让 `ApplicationModules.verify()` 直接失败。要让它可见，必须在该包上声明 `@NamedInterface`，引用方再在 `allowedDependencies` 里写 `scan::events`。

**名字必须是复数 `events`。** `main` 上已经落地的两个声明用的就是这个名字
（`scan/event/package-info.java` 与 `video/event/package-info.java`，均为
`@NamedInterface("events")`，由 `2026-08-18-scanning-important-fixes.md` Task 3
与计划 03 的执行分别落下）。写成 `scan::event` 会让 Modulith 在 `ApplicationModules`
初始化时直接抛「no named interface named 'event'」——**这不是 verify 的断言失败，
是加载期异常，整批架构测试一起挂**。`scan::spi` 那个名字是 `spi`，对得上，不用动。

**到本步为止的实际状态**（执行前用 `cat` 逐个核对，不要凭这份计划的记忆）：

| 文件 | 状态 |
|---|---|
| `scan/spi/package-info.java` | ✅ 已存在，`@NamedInterface(name = "spi")` |
| `scan/event/package-info.java` | ✅ 已存在，`@NamedInterface("events")` |
| `video/event/package-info.java` | ✅ 已存在，`@NamedInterface("events")` |
| `image/event/package-info.java` | 由**计划 04 Task 2 Step 3** 创建，`@NamedInterface("events")` |

也就是说本步正常情况下**一个文件都不用改**，只需核对四个名字都是预期的那个。
下面四段代码是核对基准，只有在某个文件确实缺失或名字不同的时候才动它。

### 两张网

| 网 | 触发点 | 覆盖什么 |
|---|---|---|
| 事件监听器 | `VideoItemCreated` / `ImageNodeCreated` | 新条目立刻有封面，用户不必等一轮完整扫描 |
| 扫描完成补齐 | `LibraryScanCompleted` | 后来才加进已有条目的剧集文件；漏掉的任务；**以及派生目录被整个删掉后的全量重建** |

两张网都走 `dedup_key`，重复排队不会产生第二个任务。

- [ ] **Step 1: 补齐命名接口声明**

以下四个文件**若已存在且内容等价则跳过**。

`src/main/java/com/mymedia/scan/spi/package-info.java`：

```java
/**
 * 扫描框架对领域模块开放的 SPI。
 *
 * <p>Spring Modulith 默认把嵌套包当作模块内部实现，必须用 {@code @NamedInterface}
 * 显式开放，引用方才能在 {@code allowedDependencies} 里写 {@code scan::spi}。
 */
@org.springframework.modulith.NamedInterface("spi")
package com.mymedia.scan.spi;
```

`src/main/java/com/mymedia/scan/event/package-info.java`：

```java
/** 扫描过程发布的领域事件，对全部模块开放。 */
@org.springframework.modulith.NamedInterface("events")
package com.mymedia.scan.event;
```

`src/main/java/com/mymedia/video/event/package-info.java`：

```java
/** 视频域发布的领域事件，供 {@code preview} / {@code metadata} 订阅。 */
@org.springframework.modulith.NamedInterface("events")
package com.mymedia.video.event;
```

`src/main/java/com/mymedia/image/event/package-info.java`：

```java
/** 图片域发布的领域事件，供 {@code preview} / {@code metadata} 订阅。 */
@org.springframework.modulith.NamedInterface("events")
package com.mymedia.image.event;
```

- [ ] **Step 2: 收紧两个领域模块的允许依赖**

这一步把「`video` / `image` 绝不引用 `preview` / `metadata`」从口头约定变成测试强制。

⚠ **`video` 与 `image` 的这两份声明已经不由本步负责了**，本步只负责 `preview` / `metadata` 自己那份：

- `video/package-info.java` 已在 `main` 上收紧（2026-08-19 审查计划 04 时补的），
  实际内容是 `{"shared", "user", "library", "scan", "scan::spi", "scan::events"}`——
  **没有 `jobs`**，视频域不排任务，多写一个用不到的依赖没有意义。
- `image/package-info.java` 由计划 04 Task 1 Step 4 从第一天就写全，带 `jobs`（要排 `ARCHIVE_INDEX`）。

下面两段保留为核对基准。若与实际不符，以「实际能让 `ModularityTests` 通过的最小集合」为准，
**不要为了对齐这份计划去给模块加它根本不用的依赖**。

`src/main/java/com/mymedia/video/package-info.java`：

```java
/**
 * 视频域：语义模型、文件名规则、Range 流式播放、播放进度、目录树视图。
 *
 * <p><b>依赖方向</b>：本模块<b>绝不</b>引用 {@code preview} / {@code metadata} /
 * {@code image}。预览与刮削是订阅本模块事件、再调用本模块公开写回 API 的下游，
 * 依赖是单向的。下面这份 {@code allowedDependencies} 就是这条约束的强制点。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Video",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan", "scan::spi", "scan::events"})
package com.mymedia.video;
```

`src/main/java/com/mymedia/image/package-info.java`：

```java
/**
 * 图片域：任意深度节点树、CBZ 流式读取、分页阅读、阅读进度。
 *
 * <p><b>依赖方向</b>：本模块<b>绝不</b>引用 {@code preview} / {@code metadata} /
 * {@code video}。与视频域共享的只有 {@code shared} 里的算法（自然排序键、物化路径），
 * <b>复用算法，不复用模型</b>。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Image",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan", "scan::spi", "scan::events"})
package com.mymedia.image;
```

`src/main/java/com/mymedia/preview/package-info.java` 的 `allowedDependencies` 改为：

```java
        allowedDependencies = {"shared", "library", "jobs", "scan", "scan::events",
                               "video", "video::events", "image", "image::events"})
```

- [ ] **Step 3: 写会失败的接线测试**

`src/test/java/com/mymedia/preview/PreviewWiringTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PreviewWiringTest.StubRunnerConfig.class)
@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class PreviewWiringTest extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRunnerConfig {

        @Bean
        @Primary
        StubCommandRunner stubCommandRunner() {
            return new StubCommandRunner();
        }
    }

    @TempDir
    Path root;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary scanVideoLibrary() throws IOException {
        Files.write(root.resolve("沙漠风暴 (2019).mp4"), new byte[2048]);
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        return library;
    }

    @Test
    void scanningEnqueuesPreviewsWithoutAnyManualTrigger() throws IOException {
        MediaLibrary library = scanVideoLibrary();

        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        Long fileId = catalogService.filesOf(item.getId()).get(0).getId();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'targetId' = ?", Integer.class, String.valueOf(fileId)))
                .isEqualTo(1);
    }

    @Test
    void endToEndScanProducesACoverAfterOneMorePoll() throws IOException {
        MediaLibrary library = scanVideoLibrary();

        jobPoller.pollOnce();   // 执行扫描排出的 PREVIEW_GENERATE

        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, item.getId())).isNotNull();
    }

    @Test
    void wipingDerivedAssetsMakesTheNextScanRebuildEverything() throws IOException {
        MediaLibrary library = scanVideoLibrary();
        jobPoller.pollOnce();
        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, item.getId())).isNotNull();

        // 模拟「派生目录被整个删掉」：清表，外键把封面引用置空
        jdbc.update("DELETE FROM derived_asset");
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, item.getId())).isNull();

        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();   // 扫描 → 补齐监听器排队
        jobPoller.pollOnce();   // 执行预览生成

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, item.getId())).isNotNull();
    }
}
```

- [ ] **Step 4: 写会失败的资源端点测试**

`src/test/java/com/mymedia/preview/AssetControllerTest.java`：

```java
package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(AssetControllerTest.StubRunnerConfig.class)
@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class AssetControllerTest extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRunnerConfig {

        @Bean
        @Primary
        StubCommandRunner stubCommandRunner() {
            return new StubCommandRunner();
        }
    }

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    private String allowedUser;
    private String strangerUser;
    private Long coverAssetId;

    @BeforeEach
    void prepare() throws IOException {
        Files.write(root.resolve("沙漠风暴 (2019).mp4"), new byte[2048]);
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();

        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        VideoFile file = catalogService.filesOf(item.getId()).get(0);
        coverAssetId = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow().getId();

        allowedUser = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount allowed = registrationService.register(allowedUser, "pw", UserRole.USER);
        accessService.grant(allowed.getId(), library.getId());

        strangerUser = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(strangerUser, "pw", UserRole.USER);
    }

    @Test
    void servesTheCoverBytesToAUserWithLibraryAccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(allowedUser, "pw")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void returnsNotFoundRatherThanForbiddenForAStranger() throws Exception {
        // 404 而非 403：不泄露资源存在性
        mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(strangerUser, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", coverAssetId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNotModifiedWhenTheEtagMatches() throws Exception {
        String etag = mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(allowedUser, "pw")))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(allowedUser, "pw"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void unknownAssetIsNotFound() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", 999_999_999L)
                        .with(httpBasic(allowedUser, "pw")))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='PreviewWiringTest,AssetControllerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -10
```

Expected: 失败——没有事件监听器，扫描不会排出 `PREVIEW_GENERATE`；`/api/assets/{id}` 返回 404（端点不存在）。

- [ ] **Step 6: 写事件监听器**

`src/main/java/com/mymedia/preview/PreviewEventListener.java`：

```java
package com.mymedia.preview;

import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 新条目一出现就排预览生成。
 *
 * <p><b>为什么是同步的 {@code @TransactionalEventListener} 而不是 Modulith 的
 * {@code @ApplicationModuleListener}</b>：本监听器只做一件事——排一个<b>持久化</b>任务。
 * 重试、租约、失败历史都由 {@code job} 表负责，再叠一层事件补发就是用两套持久化机制
 * 解决同一个问题。spec 担心的"扫描完了但缩略图没生成"由 {@link PreviewBackfill}
 * 这第二张网兜住。
 *
 * <p>{@code AFTER_COMMIT} 是必须的：事件在扫描事务里发布，提交之前
 * {@code video_file} 行对新事务不可见，这时排出去的任务会取不到数据。
 */
@Component
class PreviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(PreviewEventListener.class);

    private final VideoCatalogService videoCatalog;
    private final PreviewTrigger trigger;

    PreviewEventListener(VideoCatalogService videoCatalog, PreviewTrigger trigger) {
        this.videoCatalog = videoCatalog;
        this.trigger = trigger;
    }

    @TransactionalEventListener
    void onVideoItemCreated(VideoItemCreated event) {
        // 预览是按文件生成的（每一集都要有自己的缩略图与雪碧图），
        // 而事件是按条目发布的，所以在这里展开
        for (VideoFile file : videoCatalog.filesOf(event.itemId())) {
            trigger.requestVideoPreview(file.getId());
        }
        log.debug("已为条目 {} 的文件排队预览生成", event.itemId());
    }

    @TransactionalEventListener
    void onImageNodeCreated(ImageNodeCreated event) {
        trigger.requestImagePreview(event.nodeId());
    }
}
```

`src/main/java/com/mymedia/preview/PreviewBackfill.java`：

```java
package com.mymedia.preview;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 扫描结束时把「还没有封面」的条目补排一遍。
 *
 * <p>这是预览生成的<b>第二张网</b>，覆盖三种情况：
 * <ol>
 *   <li>后来才加进已有条目的剧集文件——它不会再触发一次 {@code VideoItemCreated}</li>
 *   <li>上一轮因为文件暂时读不到而跳过的条目</li>
 *   <li><b>派生目录被整个删掉</b>——{@code DELETE FROM derived_asset} 会经由外键
 *       把所有 {@code cover_asset_id} 置空，于是全库条目在这里被一次性重排，
 *       兑现 spec 6.2 那句「删光后可全量重建」。</li>
 * </ol>
 *
 * <p>单轮上限 {@link #BATCH_LIMIT}：一个几万条目的库不该在一次事件回调里
 * 往任务表灌几万行。没排完的下一轮扫描继续。
 */
@Component
class PreviewBackfill {

    private static final Logger log = LoggerFactory.getLogger(PreviewBackfill.class);

    private static final int BATCH_LIMIT = 500;

    private final LibraryService libraryService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final PreviewTrigger trigger;

    PreviewBackfill(LibraryService libraryService,
                    VideoCatalogService videoCatalog,
                    ImageCatalogService imageCatalog,
                    PreviewTrigger trigger) {
        this.libraryService = libraryService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.trigger = trigger;
    }

    @TransactionalEventListener
    void onScanCompleted(LibraryScanCompleted event) {
        LibraryDomain domain = libraryService.getById(event.libraryId()).getDomain();
        int queued = switch (domain) {
            case VIDEO -> backfillVideo(event.libraryId());
            case IMAGE -> backfillImage(event.libraryId());
        };
        if (queued > 0) {
            log.info("扫描完成后补排预览生成 libraryId={} 数量={}", event.libraryId(), queued);
        }
    }

    private int backfillVideo(Long libraryId) {
        List<Long> itemIds = videoCatalog.itemsWithoutCover(libraryId, BATCH_LIMIT);
        int queued = 0;
        for (Long itemId : itemIds) {
            for (VideoFile file : videoCatalog.filesOf(itemId)) {
                trigger.requestVideoPreview(file.getId());
                queued++;
            }
        }
        return queued;
    }

    private int backfillImage(Long libraryId) {
        List<Long> nodeIds = imageCatalog.nodesWithoutCover(libraryId, BATCH_LIMIT);
        nodeIds.forEach(trigger::requestImagePreview);
        return nodeIds.size();
    }
}
```

- [ ] **Step 7: 写资源访问端点**

`src/main/java/com/mymedia/preview/web/AssetController.java`：

```java
package com.mymedia.preview.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.preview.DerivedAsset;
import com.mymedia.preview.DerivedAssetService;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 派生资源的字节内容：封面、缩略图、雪碧图、雪碧图 VTT 走同一个端点。
 *
 * <p><b>访问控制沿着来源文件走</b>：派生资源自己没有归属，但它一定由某个
 * {@code scanned_file} 生成，而那个文件属于某个媒体库。于是权限判断就是
 * "调用者能不能访问那个库"，不需要给派生资源再建一套权限模型。
 *
 * <p>无权访问返回 <b>404 而非 403</b>，与项目其余端点一致：不泄露资源存在性。
 */
@RestController
@RequestMapping("/api/assets")
class AssetController {

    /** 派生资源内容变了就会换 ETag（generatedAt 变了），因此可以放心长缓存。 */
    private static final String CACHE_CONTROL = "private, max-age=604800";

    private final DerivedAssetService assetService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    AssetController(DerivedAssetService assetService,
                    ScannedFileQueryService scannedFiles,
                    LibraryAccessService accessService,
                    UserQueryService userQueryService) {
        this.assetService = assetService;
        this.scannedFiles = scannedFiles;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{id}")
    ResponseEntity<StreamingResponseBody> asset(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {

        DerivedAsset asset = assetService.getById(id);
        Long libraryId = scannedFiles.getById(asset.getSourceScannedFileId()).getLibraryId();
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到派生资源 id=" + id);
        }

        String etag = "\"asset-" + asset.getId() + "-" + asset.getGeneratedAt().toEpochMilli() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        Path path = assetService.pathOf(asset);
        if (!Files.isReadable(path)) {
            // 派生目录被清掉但数据库行还在：对调用者就是"没有"，
            // 下一轮扫描的补齐逻辑会把它重新生成出来
            throw new NotFoundException("派生资源文件不存在 id=" + id);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_TYPE, asset.getKind().contentType())
                .contentLength(asset.getSizeBytes())
                .body(writer(path));
    }

    private static StreamingResponseBody writer(Path path) {
        return (OutputStream out) -> {
            try (InputStream in = Files.newInputStream(path)) {
                in.transferTo(out);
            } catch (IOException e) {
                // 客户端提前断开是正常行为，不刷日志
            }
        };
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='PreviewWiringTest,AssetControllerTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`PreviewWiringTest` 3 个、`AssetControllerTest` 5 个用例通过，`ModularityTests` 通过（`video` / `image` 的允许依赖收紧后仍成立）。

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia/preview
git commit -m "feat: 接线预览生成事件、扫描后补齐与派生资源端点

两张网：新条目由领域事件立刻排队；扫描完成时把所有还没有封面的
条目补排一遍。后者同时兑现 spec 6.2 的'派生目录删光后可全量重建'——
DELETE FROM derived_asset 经外键把 cover_asset_id 置空，下一轮扫描全量重建。

事件监听器用同步 AFTER_COMMIT 而非 Modulith 的异步持久化监听器：
它只排一个已经持久化的任务，重试与历史由 job 表负责，不需要两套机制。

同时给 video / image 补上显式 allowedDependencies，把'领域模块绝不
引用 preview / metadata'从口头约定变成测试强制。"
```

---

## Task 7: 字段合并策略、领域写回入口与用户编辑端点

**Files:**
- Create: `src/main/java/com/mymedia/shared/ScrapeStatus.java`
- Create: `src/main/java/com/mymedia/shared/MetadataFields.java`
- Create: `src/main/java/com/mymedia/shared/MetadataPatch.java`
- Create: `src/main/java/com/mymedia/shared/MetadataSnapshot.java`
- Create: `src/main/java/com/mymedia/shared/FieldMergePolicy.java`
- Create: `src/main/java/com/mymedia/video/VideoMetadataStore.java`
- Modify: `src/main/java/com/mymedia/video/VideoCatalogService.java`（新增 4 个方法）
- Create: `src/main/java/com/mymedia/image/ImageMetadataStore.java`
- Modify: `src/main/java/com/mymedia/image/ImageCatalogService.java`（新增 4 个方法）
- Modify: `src/main/java/com/mymedia/library/LibraryService.java`（刮削器配置读写）
- Modify: `src/main/java/com/mymedia/library/LibraryController.java`（配置端点）
- Modify: `src/main/java/com/mymedia/library/LibraryDto.java`（请求体）
- Create: `src/main/java/com/mymedia/metadata/package-info.java`
- Create: `src/main/java/com/mymedia/metadata/web/MetadataDto.java`
- Create: `src/main/java/com/mymedia/metadata/web/MetadataEditController.java`
- Test: `src/test/java/com/mymedia/shared/FieldMergePolicyTest.java`
- Test: `src/test/java/com/mymedia/metadata/MetadataWriteBackTest.java`

**Interfaces:**
- Consumes: `NaturalSortKey`（计划 03 Task 1，位于 `com.mymedia.shared`）、`VideoCatalogService`、`ImageCatalogService`、`LibraryService`、`LibraryAccessService`、`UserQueryService`
- Produces:
  - `public enum ScrapeStatus { NOT_APPLICABLE, PENDING, MATCHED, NO_MATCH, NEEDS_REVIEW, ERROR }`
  - `public final class MetadataFields` — 常量 `TITLE`、`ORIGINAL_TITLE`、`SUMMARY`、`RELEASE_DATE`、`RATING`，以及 `public static final Set<String> STANDARD`
  - `public record MetadataPatch(String source, String sourceId, Map<String, String> fields, Map<String, String> extras, String rawResponse)`
  - `public record MetadataSnapshot(Map<String, String> fields, Map<String, String> fieldSources, Set<String> lockedFields, ScrapeStatus scrapeStatus, String scrapeSource, String scrapeSourceId)`
  - `public final class FieldMergePolicy` — `public static Map<String, String> apply(Map<String, String> incoming, Collection<String> lockedFields)`
  - `VideoCatalogService` 新增：`applyMetadata(Long itemId, MetadataPatch patch, ScrapeStatus status)`、`applyUserEdit(Long itemId, Map<String, String> fields)`、`updateScrapeStatus(Long itemId, ScrapeStatus status)`、`metadataOf(Long itemId)`
  - `ImageCatalogService` 新增：同名四个方法，参数为 `nodeId`
  - `LibraryService` 新增：`List<String> metadataProvidersOf(Long libraryId)`、`void setMetadataProviders(Long libraryId, List<String> providers)`
  - HTTP：`GET|PUT /api/video/items/{id}/metadata`、`GET|PUT /api/image/nodes/{id}/metadata`、`PUT /api/libraries/{id}/metadata-providers`

### 字段值一律用字符串

`MetadataPatch.fields` 的值类型是 `String`，不是 `Object`。原因：

- 提供者的原始数据本来就是字符串（NFO 是 XML、TMDB/Bangumi 是 JSON 文本），保持字符串省掉一层类型协商。
- 类型转换集中在**写回的那一条 SQL** 里（`CAST(? AS date)`、`CAST(? AS numeric)`），格式错误由数据库当场报出来，而不是在链的中途悄悄丢掉。
- 约定的规范形式只有两条：`releaseDate` 用 ISO `yyyy-MM-dd`，`rating` 用十进制小数（如 `8.4`）。

### 为什么 `applyMetadata` 要显式传 `ScrapeStatus`

状态是**链的判定结果**，不是提供者的数据：同一个 `MetadataPatch` 高置信度时是 `MATCHED`，来自文件名兜底时是 `NO_MATCH`。把它塞进 `MetadataPatch` 会让提供者去决定一件不归它管的事。

### 图片域没有 `release_date` / `rating` 列

这两个标准字段对图片域落进 `metadata` jsonb。漫画与图集的"评分"和"发行日期"远不像影视那样是一等公民，为它们加两列换不来查询上的好处。

- [ ] **Step 1: 写会失败的合并策略单元测试**

`src/test/java/com/mymedia/shared/FieldMergePolicyTest.java`：

```java
package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FieldMergePolicyTest {

    private static Map<String, String> incoming() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(MetadataFields.TITLE, "进击的巨人");
        fields.put(MetadataFields.SUMMARY, "巨人出现了");
        fields.put(MetadataFields.RATING, "8.4");
        return fields;
    }

    @Test
    void keepsEverythingWhenNothingIsLocked() {
        assertThat(FieldMergePolicy.apply(incoming(), Set.of()))
                .containsOnlyKeys(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING);
    }

    @Test
    void dropsLockedFieldsSoScrapingCannotOverwriteUserEdits() {
        Map<String, String> merged = FieldMergePolicy.apply(
                incoming(), List.of(MetadataFields.TITLE));

        assertThat(merged).doesNotContainKey(MetadataFields.TITLE);
        assertThat(merged).containsEntry(MetadataFields.SUMMARY, "巨人出现了");
    }

    @Test
    void dropsBlankValuesSoAnEmptyScrapeResultDoesNotWipeGoodData() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(MetadataFields.TITLE, "  ");
        fields.put(MetadataFields.SUMMARY, null);
        fields.put(MetadataFields.RATING, "7.0");

        assertThat(FieldMergePolicy.apply(fields, Set.of()))
                .containsExactly(Map.entry(MetadataFields.RATING, "7.0"));
    }

    @Test
    void preservesInsertionOrderSoFieldSourcesAreDeterministic() {
        assertThat(FieldMergePolicy.apply(incoming(), Set.of()).keySet())
                .containsExactly(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING);
    }

    @Test
    void emptyResultIsAllowedAndIsNotAnError() {
        assertThat(FieldMergePolicy.apply(incoming(),
                List.of(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING)))
                .isEmpty();
    }

    @Test
    void doesNotMutateTheInputMap() {
        Map<String, String> input = incoming();
        FieldMergePolicy.apply(input, List.of(MetadataFields.TITLE));

        assertThat(input).containsKey(MetadataFields.TITLE);
    }
}
```

- [ ] **Step 2: 写会失败的写回集成测试**

`src/test/java/com/mymedia/metadata/MetadataWriteBackTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MetadataWriteBackTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    VideoCatalogService videoCatalog;

    @Autowired
    ImageCatalogService imageCatalog;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    private VideoItem scanOneMovie() throws IOException {
        Files.write(root.resolve("沙漠风暴 (2019).mp4"), new byte[1024]);
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();
        return videoCatalog.findByLibrary(library.getId()).get(0);
    }

    private ImageNode scanOneGallery() throws IOException {
        Path page = root.resolve("画集/001.png");
        Files.createDirectories(page.getParent());
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", page.toFile());

        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();
        Long nodeId = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND name = '画集'",
                Long.class, library.getId());
        return imageCatalog.getNode(nodeId);
    }

    private String registerUserWithAccess() {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.ADMIN);
        accessService.grant(user.getId(), library.getId());
        return username;
    }

    private static MetadataPatch patch(String source, Map<String, String> fields) {
        return new MetadataPatch(source, "42", fields, Map.of(), "{\"raw\":true}");
    }

    @Test
    void writesStandardFieldsAndRecordsTheirSource() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(), patch("TMDB", Map.of(
                MetadataFields.TITLE, "沙漠风暴",
                MetadataFields.SUMMARY, "一支小队穿越沙漠",
                MetadataFields.RELEASE_DATE, "2019-05-01",
                MetadataFields.RATING, "7.8")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, release_date, rating, scrape_status, scrape_source,"
                        + " field_sources::text AS sources FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("沙漠风暴");
        assertThat(row.get("summary")).isEqualTo("一支小队穿越沙漠");
        assertThat(row.get("release_date")).hasToString("2019-05-01");
        assertThat(row.get("rating")).hasToString("7.8");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(row.get("scrape_source")).isEqualTo("TMDB");
        assertThat((String) row.get("sources")).contains("\"title\": \"TMDB\"");
    }

    @Test
    void keepsSortTitleInStepWithTitle() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "第2部")), ScrapeStatus.MATCHED);

        // 排序键必须跟着标题走，否则改名之后列表顺序还停在旧标题上
        assertThat(jdbc.queryForObject("SELECT sort_title FROM video_item WHERE id = ?",
                String.class, item.getId())).isNotEqualTo("第2部");
    }

    @Test
    void nonStandardFieldsLandInTheMetadataJsonb() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(), new MetadataPatch("TMDB", "42",
                Map.of(MetadataFields.TITLE, "沙漠风暴"),
                Map.of("director", "张三", "studio", "某某映画"),
                "{}"), ScrapeStatus.MATCHED);

        String metadata = jdbc.queryForObject(
                "SELECT metadata::text FROM video_item WHERE id = ?", String.class, item.getId());
        assertThat(metadata).contains("张三").contains("某某映画");
    }

    @Test
    void userEditLocksTheFieldAndLaterScrapingCannotOverwriteIt() throws IOException {
        VideoItem item = scanOneMovie();
        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "机器给的标题",
                        MetadataFields.SUMMARY, "机器给的简介")), ScrapeStatus.MATCHED);

        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "我自己改的标题"));

        // 再刮一次：标题必须纹丝不动，简介可以更新
        videoCatalog.applyMetadata(item.getId(),
                patch("BANGUMI", Map.of(MetadataFields.TITLE, "第二次刮削的标题",
                        MetadataFields.SUMMARY, "第二次刮削的简介")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, locked_fields, field_sources::text AS sources"
                        + " FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("我自己改的标题");
        assertThat(row.get("summary")).isEqualTo("第二次刮削的简介");
        assertThat((String) row.get("sources")).contains("\"title\": \"USER\"");

        MetadataSnapshot snapshot = videoCatalog.metadataOf(item.getId());
        assertThat(snapshot.lockedFields()).containsExactly(MetadataFields.TITLE);
        assertThat(snapshot.fieldSources()).containsEntry(MetadataFields.SUMMARY, "BANGUMI");
    }

    @Test
    void repeatedUserEditsDoNotDuplicateLockedFields() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "甲"));
        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "乙"));

        assertThat(videoCatalog.metadataOf(item.getId()).lockedFields())
                .containsExactly(MetadataFields.TITLE);
    }

    @Test
    void imageDomainPutsRatingAndReleaseDateIntoJsonbBecauseItHasNoSuchColumns() throws IOException {
        ImageNode node = scanOneGallery();

        imageCatalog.applyMetadata(node.getId(), patch("BANGUMI", Map.of(
                MetadataFields.TITLE, "某画集",
                MetadataFields.RATING, "9.1",
                MetadataFields.RELEASE_DATE, "2021-03-04")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, metadata::text AS metadata FROM image_node WHERE id = ?", node.getId());
        assertThat(row.get("title")).isEqualTo("某画集");
        assertThat((String) row.get("metadata")).contains("9.1").contains("2021-03-04");
    }

    @Test
    void statusCanBeUpdatedWithoutTouchingFields() throws IOException {
        VideoItem item = scanOneMovie();
        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "保留我")), ScrapeStatus.MATCHED);

        videoCatalog.updateScrapeStatus(item.getId(), ScrapeStatus.NEEDS_REVIEW);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, scrape_status FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("保留我");
        assertThat(row.get("scrape_status")).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void editEndpointAppliesAndLocks() throws Exception {
        VideoItem item = scanOneMovie();
        String username = registerUserWithAccess();

        mockMvc.perform(put("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"title\":\"手改标题\",\"summary\":\"手改简介\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFields", org.hamcrest.Matchers.hasSize(2)));

        mockMvc.perform(get("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.title").value("手改标题"))
                .andExpect(jsonPath("$.fieldSources.title").value("USER"));
    }

    @Test
    void editEndpointHidesItemsTheUserCannotAccess() throws Exception {
        VideoItem item = scanOneMovie();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void metadataProvidersAreReadableAndWritablePerLibrary() throws Exception {
        scanOneMovie();
        String admin = registerUserWithAccess();

        assertThat(libraryService.metadataProvidersOf(library.getId())).isEmpty();

        mockMvc.perform(put("/api/libraries/{id}/metadata-providers", library.getId())
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providers\":[\"LocalNfo\",\"TMDB\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("LocalNfo"));

        assertThat(libraryService.metadataProvidersOf(library.getId()))
                .containsExactly("LocalNfo", "TMDB");
    }

    @Test
    void metadataProvidersRoundTripValuesWithAwkwardCharacters() throws IOException {
        scanOneMovie();

        // text[] 的字面量语法对逗号、引号、大括号敏感，走 createArrayOf 就不用自己转义
        libraryService.setMetadataProviders(library.getId(), List.of("a,b", "c\"d", "{e}"));

        assertThat(libraryService.metadataProvidersOf(library.getId()))
                .containsExactly("a,b", "c\"d", "{e}");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='FieldMergePolicyTest,MetadataWriteBackTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`FieldMergePolicy`、`MetadataPatch` 等类不存在。

- [ ] **Step 4: 写 `shared` 里的四个类型**

`src/main/java/com/mymedia/shared/ScrapeStatus.java`：

```java
package com.mymedia.shared;

/**
 * 一个条目的刮削状态。取值与 {@code video_item} / {@code image_node} 的
 * {@code scrape_status} CHECK 约束一一对应。
 *
 * <p>放在 {@code shared} 而不是各领域模块里：两个域用的是同一套状态机，
 * {@code metadata} 模块也要写它，重复定义两遍迟早会漂移。
 */
public enum ScrapeStatus {

    /** 所属库没有配置任何刮削器——不排任务，界面零刮削噪音。 */
    NOT_APPLICABLE,

    /** 待刮削。 */
    PENDING,

    /** 高置信度命中并已应用。 */
    MATCHED,

    /** 没找到。<b>这是正常状态不是错误</b>，界面安静回落到文件名元数据。 */
    NO_MATCH,

    /** 有候选但置信度不够，等用户确认。 */
    NEEDS_REVIEW,

    /** 网络故障或限流，会按退避重试。 */
    ERROR
}
```

`src/main/java/com/mymedia/shared/MetadataFields.java`：

```java
package com.mymedia.shared;

import java.util.Set;

/**
 * 标准元数据字段名。
 *
 * <p>这些字符串同时是三个地方的键：{@code MetadataPatch.fields} 的键、
 * {@code field_sources} jsonb 的键、{@code locked_fields} 数组的元素。
 * 定成常量而不是各处写字面量——拼错一个字母就会让锁定失效，而那是静默失败。
 */
public final class MetadataFields {

    public static final String TITLE = "title";
    public static final String ORIGINAL_TITLE = "originalTitle";
    public static final String SUMMARY = "summary";

    /** ISO 日期，形如 {@code 2019-05-01}。 */
    public static final String RELEASE_DATE = "releaseDate";

    /** 十进制小数，形如 {@code 8.4}。 */
    public static final String RATING = "rating";

    /** 标准字段全集。不在其中的键会落进 {@code metadata} jsonb。 */
    public static final Set<String> STANDARD =
            Set.of(TITLE, ORIGINAL_TITLE, SUMMARY, RELEASE_DATE, RATING);

    private MetadataFields() {
    }
}
```

`src/main/java/com/mymedia/shared/MetadataPatch.java`：

```java
package com.mymedia.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个提供者对某个条目给出的元数据。
 *
 * <p>字段值一律是 {@code String}：提供者的原始数据本来就是文本（NFO 是 XML、
 * 各家 API 是 JSON），保持字符串省掉一层类型协商，类型转换集中在写回的那条 SQL 里
 * （{@code CAST(? AS date)}），格式错误当场报出来而不是在链的中途悄悄丢掉。
 *
 * @param source      提供者名，会写进 {@code field_sources} 与 {@code scrape_source}
 * @param sourceId    该提供者侧的条目 id，写进 {@code scrape_source_id}
 * @param fields      标准字段（键取自 {@link MetadataFields}）
 * @param extras      类型特有字段（导演、演员、画师…），落进 {@code metadata} jsonb
 * @param rawResponse 提供者的原始响应，原样存进 {@code raw_metadata}
 */
public record MetadataPatch(
        String source,
        String sourceId,
        Map<String, String> fields,
        Map<String, String> extras,
        String rawResponse) {

    public MetadataPatch {
        fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
        extras = extras == null ? Map.of() : new LinkedHashMap<>(extras);
    }

    public boolean isEmpty() {
        return fields.isEmpty() && extras.isEmpty();
    }
}
```

`src/main/java/com/mymedia/shared/MetadataSnapshot.java`：

```java
package com.mymedia.shared;

import java.util.Map;
import java.util.Set;

/**
 * 一个条目当前的元数据全貌，供编辑界面展示。
 *
 * <p>{@code fieldSources} 只用来展示"这个字段是谁写的"，<b>不参与任何判定</b>；
 * 覆盖保护全部由 {@code lockedFields} 表达。
 */
public record MetadataSnapshot(
        Map<String, String> fields,
        Map<String, String> fieldSources,
        Set<String> lockedFields,
        ScrapeStatus scrapeStatus,
        String scrapeSource,
        String scrapeSourceId) {
}
```

`src/main/java/com/mymedia/shared/FieldMergePolicy.java`：

```java
package com.mymedia.shared;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 刮削结果写回前的过滤。
 *
 * <p>只有两条规则，spec 7.2 的优先级由它和"链的顺序"共同表达，<b>不存在第三套机制</b>：
 * <ol>
 *   <li><b>跳过锁定字段</b>——用户编辑过的字段任何刮削都不得覆盖。</li>
 *   <li><b>跳过空值</b>——提供者没给出的字段不该把已有的好数据洗成空。</li>
 * </ol>
 *
 * <p>用户编辑走的是另一条路（{@code applyUserEdit}），<b>不经过本类</b>：
 * 用户就是权威，没有什么能拦住他改自己锁过的字段。
 */
public final class FieldMergePolicy {

    private FieldMergePolicy() {
    }

    public static Map<String, String> apply(Map<String, String> incoming,
                                            Collection<String> lockedFields) {
        Set<String> locked = Set.copyOf(lockedFields);
        Map<String, String> result = new LinkedHashMap<>();
        incoming.forEach((field, value) -> {
            if (value == null || value.isBlank() || locked.contains(field)) {
                return;
            }
            result.put(field, value);
        });
        return result;
    }
}
```

- [ ] **Step 5: 写视频域的元数据存储**

`src/main/java/com/mymedia/video/VideoMetadataStore.java`：

```java
package com.mymedia.video;

import tools.jackson.databind.ObjectMapper;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NaturalSortKey;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code video_item} 上四个非标量列的读写：
 * {@code metadata} / {@code raw_metadata} / {@code field_sources} / {@code locked_fields}。
 *
 * <p>全部走 {@link JdbcTemplate}，与计划 03、04 一致：jsonb 与 text[] 一律不做 JPA 映射
 * （{@code ddl-auto=validate} 对 Hibernate 的这两类映射很挑），而它们天然适合直读直写。
 *
 * <p>合并用 PostgreSQL 的 {@code ||}：jsonb 的 {@code ||} 是右侧覆盖左侧的浅合并，
 * 正好是"只更新这次写到的字段"；text[] 的 {@code ||} 是拼接，去重靠外面套一层
 * {@code SELECT DISTINCT unnest}。
 */
@Component
class VideoMetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    VideoMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> lockedFields(Long itemId) {
        return jdbc.queryForObject(
                "SELECT locked_fields FROM video_item WHERE id = ?",
                (rs, rowNum) -> toSet(rs.getArray(1)), itemId);
    }

    /**
     * 写入一批字段。
     *
     * <p>每个标量列都是 {@code COALESCE(?, 列)}：这次没写到的字段保持原值。
     * 日期与评分交给数据库 CAST，格式不对当场报错。
     */
    void applyFields(Long itemId, Map<String, String> fields, Map<String, String> extras,
                     String source, String sourceId, String rawResponse, ScrapeStatus status) {

        String title = fields.get(MetadataFields.TITLE);
        Map<String, String> sources = new LinkedHashMap<>();
        fields.keySet().forEach(field -> sources.put(field, source));
        extras.keySet().forEach(field -> sources.put(field, source));

        jdbc.update("""
                UPDATE video_item
                   SET title          = COALESCE(?, title),
                       sort_title     = COALESCE(?, sort_title),
                       original_title = COALESCE(?, original_title),
                       summary        = COALESCE(?, summary),
                       release_date   = COALESCE(CAST(? AS date), release_date),
                       rating         = COALESCE(CAST(? AS numeric), rating),
                       metadata       = metadata || CAST(? AS jsonb),
                       raw_metadata   = COALESCE(CAST(? AS jsonb), raw_metadata),
                       field_sources  = field_sources || CAST(? AS jsonb),
                       scrape_source  = COALESCE(?, scrape_source),
                       scrape_source_id = COALESCE(?, scrape_source_id),
                       scrape_status  = ?
                 WHERE id = ?
                """,
                title,
                title == null ? null : NaturalSortKey.of(title),
                fields.get(MetadataFields.ORIGINAL_TITLE),
                fields.get(MetadataFields.SUMMARY),
                fields.get(MetadataFields.RELEASE_DATE),
                fields.get(MetadataFields.RATING),
                toJson(extras),
                rawResponse,
                toJson(sources),
                source, sourceId, status.name(), itemId);
    }

    /** 把字段名加进锁定集合，去重。 */
    void lock(Long itemId, Set<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE video_item
                       SET locked_fields = ARRAY(SELECT DISTINCT unnest(locked_fields || ?))
                     WHERE id = ?
                    """);
            statement.setArray(1, connection.createArrayOf("text", fields.toArray(String[]::new)));
            statement.setLong(2, itemId);
            return statement;
        });
    }

    void updateStatus(Long itemId, ScrapeStatus status) {
        jdbc.update("UPDATE video_item SET scrape_status = ? WHERE id = ?", status.name(), itemId);
    }

    MetadataSnapshot snapshot(Long itemId) {
        return jdbc.queryForObject("""
                SELECT title, original_title, summary, release_date, rating,
                       field_sources::text AS sources, locked_fields,
                       scrape_status, scrape_source, scrape_source_id
                  FROM video_item WHERE id = ?
                """, (rs, rowNum) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, MetadataFields.TITLE, rs.getString("title"));
            putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, rs.getString("original_title"));
            putIfPresent(fields, MetadataFields.SUMMARY, rs.getString("summary"));
            putIfPresent(fields, MetadataFields.RELEASE_DATE, rs.getString("release_date"));
            putIfPresent(fields, MetadataFields.RATING, rs.getString("rating"));
            return new MetadataSnapshot(
                    fields,
                    fromJson(rs.getString("sources")),
                    toSet(rs.getArray("locked_fields")),
                    ScrapeStatus.valueOf(rs.getString("scrape_status")),
                    rs.getString("scrape_source"),
                    rs.getString("scrape_source_id"));
        }, itemId);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static Set<String> toSet(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of((String[]) array.getArray()));
    }

    private static String toJson(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化元数据字段", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fromJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 field_sources", e);
        }
    }
}
```

在 `src/main/java/com/mymedia/video/VideoCatalogService.java` 中新增依赖 `VideoMetadataStore metadataStore` 并追加：

```java
    /**
     * 应用一次刮削结果。
     *
     * <p>{@code status} 由调用方（刮削链）决定而不是塞进 {@link MetadataPatch}：
     * 同一份数据高置信度时是 {@code MATCHED}，来自文件名兜底时是 {@code NO_MATCH}，
     * 这是链的判定不是提供者的数据。
     */
    @Transactional
    public void applyMetadata(Long itemId, MetadataPatch patch, ScrapeStatus status) {
        Map<String, String> fields = FieldMergePolicy.apply(
                patch.fields(), metadataStore.lockedFields(itemId));
        Map<String, String> extras = FieldMergePolicy.apply(
                patch.extras(), metadataStore.lockedFields(itemId));
        metadataStore.applyFields(itemId, fields, extras,
                patch.source(), patch.sourceId(), patch.rawResponse(), status);
    }

    /**
     * 用户手工编辑。
     *
     * <p><b>不经过 {@link FieldMergePolicy}</b>——用户就是权威，可以改自己锁过的字段。
     * 写入的同时把这些字段加进 {@code locked_fields}，此后任何刮削都覆盖不了它们。
     */
    @Transactional
    public void applyUserEdit(Long itemId, Map<String, String> fields) {
        metadataStore.applyFields(itemId, fields, Map.of(), "USER", null, null,
                metadataStore.snapshot(itemId).scrapeStatus());
        metadataStore.lock(itemId, fields.keySet());
    }

    @Transactional
    public void updateScrapeStatus(Long itemId, ScrapeStatus status) {
        metadataStore.updateStatus(itemId, status);
    }

    @Transactional(readOnly = true)
    public MetadataSnapshot metadataOf(Long itemId) {
        return metadataStore.snapshot(itemId);
    }
```

需要新增的 import：`com.mymedia.shared.FieldMergePolicy`、`com.mymedia.shared.MetadataPatch`、`com.mymedia.shared.MetadataSnapshot`、`com.mymedia.shared.ScrapeStatus`、`java.util.Map`。

- [ ] **Step 6: 写图片域的元数据存储**

`src/main/java/com/mymedia/image/ImageMetadataStore.java`：与 `VideoMetadataStore` 结构相同，差别只在**列的映射**：

```java
package com.mymedia.image;

import tools.jackson.databind.ObjectMapper;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code image_node} 上非标量列的读写。
 *
 * <p>与视频域的差别只有一处：{@code image_node} <b>没有 release_date 与 rating 列</b>，
 * 这两个标准字段落进 {@code metadata} jsonb。漫画与图集的"评分""发行日期"
 * 远不像影视那样是一等公民，为它们加两列换不来查询上的好处。
 */
@Component
class ImageMetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 图片域没有对应列、要落进 jsonb 的标准字段。 */
    private static final Set<String> JSONB_ONLY_FIELDS =
            Set.of(MetadataFields.RELEASE_DATE, MetadataFields.RATING,
                   MetadataFields.ORIGINAL_TITLE);

    private final JdbcTemplate jdbc;

    ImageMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> lockedFields(Long nodeId) {
        return jdbc.queryForObject(
                "SELECT locked_fields FROM image_node WHERE id = ?",
                (rs, rowNum) -> toSet(rs.getArray(1)), nodeId);
    }

    void applyFields(Long nodeId, Map<String, String> fields, Map<String, String> extras,
                     String source, String sourceId, String rawResponse, ScrapeStatus status) {

        Map<String, String> jsonbPayload = new LinkedHashMap<>(extras);
        fields.forEach((field, value) -> {
            if (JSONB_ONLY_FIELDS.contains(field)) {
                jsonbPayload.put(field, value);
            }
        });

        Map<String, String> sources = new LinkedHashMap<>();
        fields.keySet().forEach(field -> sources.put(field, source));
        extras.keySet().forEach(field -> sources.put(field, source));

        jdbc.update("""
                UPDATE image_node
                   SET title         = COALESCE(?, title),
                       summary       = COALESCE(?, summary),
                       metadata      = metadata || CAST(? AS jsonb),
                       raw_metadata  = COALESCE(CAST(? AS jsonb), raw_metadata),
                       field_sources = field_sources || CAST(? AS jsonb),
                       scrape_source = COALESCE(?, scrape_source),
                       scrape_source_id = COALESCE(?, scrape_source_id),
                       scrape_status = ?
                 WHERE id = ?
                """,
                fields.get(MetadataFields.TITLE),
                fields.get(MetadataFields.SUMMARY),
                toJson(jsonbPayload),
                rawResponse,
                toJson(sources),
                source, sourceId, status.name(), nodeId);
    }

    void lock(Long nodeId, Set<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE image_node
                       SET locked_fields = ARRAY(SELECT DISTINCT unnest(locked_fields || ?))
                     WHERE id = ?
                    """);
            statement.setArray(1, connection.createArrayOf("text", fields.toArray(String[]::new)));
            statement.setLong(2, nodeId);
            return statement;
        });
    }

    void updateStatus(Long nodeId, ScrapeStatus status) {
        jdbc.update("UPDATE image_node SET scrape_status = ? WHERE id = ?", status.name(), nodeId);
    }

    MetadataSnapshot snapshot(Long nodeId) {
        return jdbc.queryForObject("""
                SELECT title, summary, metadata::text AS metadata, field_sources::text AS sources,
                       locked_fields, scrape_status, scrape_source, scrape_source_id
                  FROM image_node WHERE id = ?
                """, (rs, rowNum) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, MetadataFields.TITLE, rs.getString("title"));
            putIfPresent(fields, MetadataFields.SUMMARY, rs.getString("summary"));
            // 落在 jsonb 里的标准字段也要还原到统一视图上，界面不该关心它存在哪儿
            fromJson(rs.getString("metadata")).forEach((key, value) -> {
                if (MetadataFields.STANDARD.contains(key)) {
                    fields.put(key, value);
                }
            });
            return new MetadataSnapshot(
                    fields,
                    fromJson(rs.getString("sources")),
                    toSet(rs.getArray("locked_fields")),
                    ScrapeStatus.valueOf(rs.getString("scrape_status")),
                    rs.getString("scrape_source"),
                    rs.getString("scrape_source_id"));
        }, nodeId);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static Set<String> toSet(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of((String[]) array.getArray()));
    }

    private static String toJson(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化元数据字段", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fromJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析元数据 JSON", e);
        }
    }
}
```

在 `src/main/java/com/mymedia/image/ImageCatalogService.java` 中新增依赖 `ImageMetadataStore metadataStore`，并追加与视频域**同名同签名**的四个方法（参数名为 `nodeId`）：

```java
    @Transactional
    public void applyMetadata(Long nodeId, MetadataPatch patch, ScrapeStatus status) {
        Set<String> locked = metadataStore.lockedFields(nodeId);
        metadataStore.applyFields(nodeId,
                FieldMergePolicy.apply(patch.fields(), locked),
                FieldMergePolicy.apply(patch.extras(), locked),
                patch.source(), patch.sourceId(), patch.rawResponse(), status);
    }

    @Transactional
    public void applyUserEdit(Long nodeId, Map<String, String> fields) {
        metadataStore.applyFields(nodeId, fields, Map.of(), "USER", null, null,
                metadataStore.snapshot(nodeId).scrapeStatus());
        metadataStore.lock(nodeId, fields.keySet());
    }

    @Transactional
    public void updateScrapeStatus(Long nodeId, ScrapeStatus status) {
        metadataStore.updateStatus(nodeId, status);
    }

    @Transactional(readOnly = true)
    public MetadataSnapshot metadataOf(Long nodeId) {
        return metadataStore.snapshot(nodeId);
    }
```

- [ ] **Step 7: 媒体库的刮削器配置**

在 `src/main/java/com/mymedia/library/LibraryService.java` 中新增依赖 `org.springframework.jdbc.core.JdbcTemplate jdbc` 并追加：

```java
    /**
     * 该库配置的刮削器名单，顺序即尝试顺序。
     *
     * <p>空数组表示<b>该库不刮削</b>：其条目直接置 {@code NOT_APPLICABLE}，
     * 连任务都不排，界面上零刮削噪音。
     *
     * <p>{@code metadata_providers} 是 {@code text[]}，按项目约定不做 JPA 映射，
     * 走 {@link JdbcTemplate} 直读直写。
     */
    @Transactional(readOnly = true)
    public List<String> metadataProvidersOf(Long libraryId) {
        return jdbc.queryForObject(
                "SELECT metadata_providers FROM libraries WHERE id = ?",
                (rs, rowNum) -> {
                    java.sql.Array array = rs.getArray(1);
                    return array == null ? List.<String>of() : List.of((String[]) array.getArray());
                }, libraryId);
    }

    /**
     * 覆盖该库的刮削器名单。
     *
     * <p>用 {@code createArrayOf} 而不是拼 {@code '{a,b}'} 字面量：后者对逗号、
     * 双引号、大括号都要自己转义，出错方式是静默写错数据而不是报错。
     */
    @Transactional
    public void setMetadataProviders(Long libraryId, List<String> providers) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "UPDATE libraries SET metadata_providers = ? WHERE id = ?");
            statement.setArray(1, connection.createArrayOf("text", providers.toArray(String[]::new)));
            statement.setLong(2, libraryId);
            return statement;
        });
    }
```

在 `src/main/java/com/mymedia/library/LibraryDto.java` 中追加：

```java
    record MetadataProvidersRequest(@NotNull List<String> providers) {
    }
```

（需要 import `java.util.List`。）

在 `src/main/java/com/mymedia/library/LibraryController.java` 中追加：

```java
    @GetMapping("/{id}/metadata-providers")
    List<String> metadataProviders(@AuthenticationPrincipal UserDetails principal,
                                   @PathVariable Long id) {
        if (!accessService.canAccess(currentUserId(principal), id)) {
            throw new NotFoundException("找不到媒体库 id=" + id);
        }
        return libraryService.metadataProvidersOf(id);
    }

    @PutMapping("/{id}/metadata-providers")
    @PreAuthorize("hasRole('ADMIN')")
    List<String> setMetadataProviders(@PathVariable Long id,
                                      @Valid @RequestBody LibraryDto.MetadataProvidersRequest request) {
        libraryService.getById(id);   // 不存在就 404
        libraryService.setMetadataProviders(id, request.providers());
        return libraryService.metadataProvidersOf(id);
    }
```

（需要 import `org.springframework.web.bind.annotation.PutMapping`。）

- [ ] **Step 8: 建立 `metadata` 模块与编辑端点**

`src/main/java/com/mymedia/metadata/package-info.java`：

```java
/**
 * 元数据：提供者链、刮削任务、待确认队列、用户编辑。
 *
 * <p><b>依赖方向是单向的</b>：本模块订阅 {@code video} / {@code image} 的领域事件
 * 并调用它们的公开写回 API，两个领域模块绝不反向引用本模块。
 *
 * <p><b>为什么这里不像 {@code scan} 那样做 SPI 倒置</b>：物理扫描真正与领域无关，
 * 倒置能让"加第三个域不改扫描代码"成立；而刮削本身就是领域特定的
 * （TMDB 管电影、Bangumi 管番剧与漫画），倒置只会把 if/else 换个地方摆，
 * 还多一层间接。详见 ADR-004。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Metadata",
        allowedDependencies = {"shared", "user", "library", "video", "image"})
package com.mymedia.metadata;
```

`src/main/java/com/mymedia/metadata/web/MetadataDto.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.shared.MetadataSnapshot;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.Set;

final class MetadataDto {

    private MetadataDto() {
    }

    record EditRequest(@NotNull Map<String, String> fields) {
    }

    record Response(
            Map<String, String> fields,
            Map<String, String> fieldSources,
            Set<String> lockedFields,
            String scrapeStatus,
            String scrapeSource,
            String scrapeSourceId) {

        static Response from(MetadataSnapshot snapshot) {
            return new Response(
                    snapshot.fields(),
                    snapshot.fieldSources(),
                    snapshot.lockedFields(),
                    snapshot.scrapeStatus().name(),
                    snapshot.scrapeSource(),
                    snapshot.scrapeSourceId());
        }
    }
}
```

`src/main/java/com/mymedia/metadata/web/MetadataEditController.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户编辑元数据与字段锁定。
 *
 * <p>URL 仍然按领域切分（{@code /api/video/items/...} 与 {@code /api/image/nodes/...}），
 * 但实现住在 {@code metadata} 模块：<b>接口按领域切分是对外部 URL 的要求，
 * 不要求实现类住在哪个模块</b>。两个域的编辑用的是同一套字段模型与同一套锁定语义，
 * 放在一起省掉一份重复的 DTO 与控制器，也保住了"领域模块不引用 metadata"的方向。
 */
@RestController
class MetadataEditController {

    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    MetadataEditController(VideoCatalogService videoCatalog,
                           ImageCatalogService imageCatalog,
                           LibraryAccessService accessService,
                           UserQueryService userQueryService) {
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/video/items/{id}/metadata")
    MetadataDto.Response videoMetadata(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id) {
        requireVideoAccess(principal, id);
        return MetadataDto.Response.from(videoCatalog.metadataOf(id));
    }

    @PutMapping("/api/video/items/{id}/metadata")
    MetadataDto.Response editVideoMetadata(@AuthenticationPrincipal UserDetails principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody MetadataDto.EditRequest request) {
        requireVideoAccess(principal, id);
        videoCatalog.applyUserEdit(id, request.fields());
        return MetadataDto.Response.from(videoCatalog.metadataOf(id));
    }

    @GetMapping("/api/image/nodes/{id}/metadata")
    MetadataDto.Response imageMetadata(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id) {
        requireImageAccess(principal, id);
        return MetadataDto.Response.from(imageCatalog.metadataOf(id));
    }

    @PutMapping("/api/image/nodes/{id}/metadata")
    MetadataDto.Response editImageMetadata(@AuthenticationPrincipal UserDetails principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody MetadataDto.EditRequest request) {
        requireImageAccess(principal, id);
        imageCatalog.applyUserEdit(id, request.fields());
        return MetadataDto.Response.from(imageCatalog.metadataOf(id));
    }

    private void requireVideoAccess(UserDetails principal, Long itemId) {
        Long libraryId = videoCatalog.getItem(itemId).getLibraryId();
        if (!accessService.canAccess(currentUserId(principal), libraryId)) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
    }

    private void requireImageAccess(UserDetails principal, Long nodeId) {
        Long libraryId = imageCatalog.getNode(nodeId).getLibraryId();
        if (!accessService.canAccess(currentUserId(principal), libraryId)) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

- [ ] **Step 9: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='FieldMergePolicyTest,MetadataWriteBackTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`FieldMergePolicyTest` 6 个、`MetadataWriteBackTest` 11 个用例通过。

- [ ] **Step 10: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia
git commit -m "feat: 添加字段合并策略、领域写回入口与用户编辑端点

spec 7.2 的优先级用两套既有机制表达，不引入第三套 tier 比较：
链的顺序表达'谁先谁后'，locked_fields 表达'谁不能被覆盖'。
field_sources 只用于展示，不参与判定。

用户编辑不经过 FieldMergePolicy——用户就是权威，可以改自己锁过的字段。

image_node 没有 release_date / rating 列，这两个标准字段落进 metadata jsonb，
快照读取时再还原到统一视图上，界面不必关心它存在哪儿。"
```

---

## Task 8: `MetadataProvider` SPI 与本地元数据文件

**Files:**
- Create: `src/main/java/com/mymedia/metadata/ScrapeSubject.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataCandidate.java`
- Create: `src/main/java/com/mymedia/metadata/ProviderUnavailableException.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataProvider.java`
- Create: `src/main/java/com/mymedia/metadata/ParsedNfo.java`
- Create: `src/main/java/com/mymedia/metadata/NfoParser.java`
- Create: `src/main/java/com/mymedia/metadata/LocalNfoProvider.java`
- Test: `src/test/java/com/mymedia/metadata/NfoParserTest.java`
- Test: `src/test/java/com/mymedia/metadata/LocalNfoProviderTest.java`

**Interfaces:**
- Consumes: `LibraryDomain`（计划 01）、`MetadataPatch`、`MetadataFields`（Task 7）
- Produces:
  - `public record ScrapeSubject(LibraryDomain domain, Long targetId, Long libraryId, String title, Integer year, Path primaryPath)`
  - `public record MetadataCandidate(String provider, String externalId, String title, Integer year, double score, String payload)`
  - `public class ProviderUnavailableException extends RuntimeException`
  - `public interface MetadataProvider` — `name()`、`supports(LibraryDomain)`、`available()`（default true）、`search(ScrapeSubject)`、`fetch(ScrapeSubject, MetadataCandidate)`
  - `record ParsedNfo(Map<String, String> fields, Map<String, String> extras)`（package-private）
  - `class NfoParser`（package-private）— `static ParsedNfo parseXml(String xml)`、`static ParsedNfo parseJson(String json)`
  - `class LocalNfoProvider implements MetadataProvider`（package-private，Spring bean）— `static final String NAME = "LocalNfo"`

### 本地元数据文件解决的是演示数据的难题

spec §7.2 规则 6：seed 数据全部依靠本地 NFO 提供元数据，**`docker compose up` 无需任何 API key**。这不是锦上添花——没有它，演示环境要么没有元数据，要么要求看简历的人先去 TMDB 注册。

### XML 解析必须关掉 DTD

`.nfo` 是用户放在媒体目录里的文件，内容不可信。JDK 的 `DocumentBuilderFactory` 默认允许 DOCTYPE 与外部实体，一个精心构造的 `.nfo` 就能读走服务器上的任意文件（XXE）或撑爆内存（十亿笑声）。**`disallow-doctype-decl` 必须设为 true**，本任务的测试有一条专门守这个。

- [ ] **Step 1: 写会失败的解析器单元测试**

`src/test/java/com/mymedia/metadata/NfoParserTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.shared.MetadataFields;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NfoParserTest {

    private static final String KODI_MOVIE_NFO = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <movie>
              <title>大雄兔</title>
              <originaltitle>Big Buck Bunny</originaltitle>
              <plot>一只巨兔与三个坏蛋的故事。</plot>
              <premiered>2008-05-20</premiered>
              <rating>7.9</rating>
              <director>Sacha Goedegebure</director>
              <studio>Blender Foundation</studio>
              <genre>动画</genre>
              <genre>喜剧</genre>
            </movie>
            """;

    @Test
    void readsStandardFieldsFromAKodiMovieNfo() {
        ParsedNfo parsed = NfoParser.parseXml(KODI_MOVIE_NFO);

        assertThat(parsed.fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "Big Buck Bunny")
                .containsEntry(MetadataFields.SUMMARY, "一只巨兔与三个坏蛋的故事。")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20")
                .containsEntry(MetadataFields.RATING, "7.9");
    }

    @Test
    void putsTypeSpecificTagsIntoExtras() {
        ParsedNfo parsed = NfoParser.parseXml(KODI_MOVIE_NFO);

        assertThat(parsed.extras())
                .containsEntry("director", "Sacha Goedegebure")
                .containsEntry("studio", "Blender Foundation")
                .containsEntry("genres", "动画, 喜剧");
    }

    @Test
    void acceptsTvshowRootAsWellAsMovie() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <tvshow><title>某番剧</title><plot>简介</plot></tvshow>
                """);

        assertThat(parsed.fields()).containsEntry(MetadataFields.TITLE, "某番剧");
    }

    @Test
    void fallsBackFromPremieredToYear() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <movie><title>老片</title><year>1998</year></movie>
                """);

        // 只有年份时补成当年 1 月 1 日，让 release_date 列有个能排序的值
        assertThat(parsed.fields()).containsEntry(MetadataFields.RELEASE_DATE, "1998-01-01");
    }

    @Test
    void ignoresEmptyTagsInsteadOfWritingBlanks() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <movie><title>有标题</title><plot></plot><rating>   </rating></movie>
                """);

        assertThat(parsed.fields()).containsOnlyKeys(MetadataFields.TITLE);
    }

    @Test
    void rejectsDoctypeDeclarationsToBlockXxe() {
        // .nfo 是用户放在媒体目录里的文件，内容不可信
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <movie><title>&xxe;</title></movie>
                """;

        assertThatThrownBy(() -> NfoParser.parseXml(malicious))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unparseableXmlRaisesRatherThanReturningEmpty() {
        assertThatThrownBy(() -> NfoParser.parseXml("<movie><title>没闭合"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheProjectsOwnMetadataJsonSchema() {
        ParsedNfo parsed = NfoParser.parseJson("""
                {
                  "title": "自制视频",
                  "summary": "2024 年家庭录像",
                  "releaseDate": "2024-08-01",
                  "rating": "9.9",
                  "extras": {"photographer": "我自己"}
                }
                """);

        assertThat(parsed.fields())
                .containsEntry(MetadataFields.TITLE, "自制视频")
                .containsEntry(MetadataFields.RELEASE_DATE, "2024-08-01");
        assertThat(parsed.extras()).containsEntry("photographer", "我自己");
    }

    @Test
    void metadataJsonWithoutExtrasIsFine() {
        ParsedNfo parsed = NfoParser.parseJson("{\"title\":\"只有标题\"}");

        assertThat(parsed.fields()).containsOnlyKeys(MetadataFields.TITLE);
        assertThat(parsed.extras()).isEmpty();
    }
}
```

- [ ] **Step 2: 写会失败的提供者测试**

`src/test/java/com/mymedia/metadata/LocalNfoProviderTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalNfoProviderTest {

    private final LocalNfoProvider provider = new LocalNfoProvider();

    @TempDir
    Path dir;

    private static final String NFO = """
            <movie><title>大雄兔</title><plot>一只巨兔。</plot><premiered>2008-05-20</premiered></movie>
            """;

    private ScrapeSubject subjectFor(Path primaryPath) {
        return new ScrapeSubject(LibraryDomain.VIDEO, 1L, 1L, "文件名标题", null, primaryPath);
    }

    private Path writeVideo(String name) throws IOException {
        Path video = dir.resolve(name);
        Files.write(video, new byte[16]);
        return video;
    }

    @Test
    void findsSiblingNfoNamedAfterTheVideoFile() throws IOException {
        Path video = writeVideo("大雄兔.mp4");
        Files.writeString(dir.resolve("大雄兔.nfo"), NFO, StandardCharsets.UTF_8);

        List<MetadataCandidate> candidates = provider.search(subjectFor(video));

        assertThat(candidates).hasSize(1);
        // 本地文件是用户自己写的，没有"可能不对"这回事
        assertThat(candidates.get(0).score()).isEqualTo(1.0);
        assertThat(candidates.get(0).provider()).isEqualTo(LocalNfoProvider.NAME);
    }

    @Test
    void fallsBackToMovieNfoInTheSameDirectory() throws IOException {
        Path video = writeVideo("VIDEO_TS.mp4");
        Files.writeString(dir.resolve("movie.nfo"), NFO, StandardCharsets.UTF_8);

        assertThat(provider.search(subjectFor(video))).hasSize(1);
    }

    @Test
    void fetchesFieldsAndKeepsTheRawFile() throws IOException {
        Path video = writeVideo("大雄兔.mp4");
        Files.writeString(dir.resolve("大雄兔.nfo"), NFO, StandardCharsets.UTF_8);
        ScrapeSubject subject = subjectFor(video);

        Optional<MetadataPatch> patch = provider.fetch(subject, provider.search(subject).get(0));

        assertThat(patch).isPresent();
        assertThat(patch.get().source()).isEqualTo(LocalNfoProvider.NAME);
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20");
        assertThat(patch.get().rawResponse()).contains("<movie>");
    }

    @Test
    void readsMetadataJsonWhenNoNfoIsPresent() throws IOException {
        Path video = writeVideo("家庭录像.mp4");
        Files.writeString(dir.resolve("metadata.json"),
                "{\"title\":\"家庭录像 2024\"}", StandardCharsets.UTF_8);
        ScrapeSubject subject = subjectFor(video);

        Optional<MetadataPatch> patch = provider.fetch(subject, provider.search(subject).get(0));

        assertThat(patch.orElseThrow().fields())
                .containsEntry(MetadataFields.TITLE, "家庭录像 2024");
    }

    @Test
    void looksInsideTheDirectoryWhenTheSubjectIsADirectory() throws IOException {
        Path book = Files.createDirectory(dir.resolve("某画集"));
        Files.writeString(book.resolve("metadata.json"),
                "{\"title\":\"某画集\"}", StandardCharsets.UTF_8);

        ScrapeSubject subject = new ScrapeSubject(
                LibraryDomain.IMAGE, 2L, 1L, "某画集", null, book);

        assertThat(provider.search(subject)).hasSize(1);
    }

    @Test
    void returnsNoCandidateWhenThereIsNoLocalFile() throws IOException {
        assertThat(provider.search(subjectFor(writeVideo("孤零零.mp4")))).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenThePathIsUnknown() {
        assertThat(provider.search(new ScrapeSubject(
                LibraryDomain.VIDEO, 1L, 1L, "标题", null, null))).isEmpty();
    }

    @Test
    void supportsBothDomainsAndIsAlwaysAvailable() {
        assertThat(provider.supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(provider.supports(LibraryDomain.IMAGE)).isTrue();
        assertThat(provider.available()).isTrue();
    }

    @Test
    void aBrokenNfoIsReportedAsNoResultRatherThanCrashingTheChain() throws IOException {
        Path video = writeVideo("坏文件.mp4");
        Files.writeString(dir.resolve("坏文件.nfo"), "<movie><title>没闭合");
        ScrapeSubject subject = subjectFor(video);

        // search 仍然报告"这里有个文件"，fetch 时才发现读不了——
        // 此时返回空而不是抛异常：一个坏 NFO 不该让整条链失败
        assertThat(provider.fetch(subject, provider.search(subject).get(0))).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='NfoParserTest,LocalNfoProviderTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`NfoParser`、`LocalNfoProvider` 不存在。

- [ ] **Step 4: 写 SPI 三件套**

`src/main/java/com/mymedia/metadata/ScrapeSubject.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;

import java.nio.file.Path;

/**
 * 一个待刮削的条目，已经把两个域的差异抹平成提供者需要的最小信息。
 *
 * @param domain      决定哪些提供者适用（TMDB 只管视频，Bangumi 两个域都能管）
 * @param targetId    {@code video_item.id} 或 {@code image_node.id}
 * @param libraryId   所属媒体库
 * @param title       当前标题（扫描时由文件名/目录名得出），搜索词就是它
 * @param year        从文件名里认出的年份，用于提高匹配置信度；认不出就是 {@code null}
 * @param primaryPath 该条目在磁盘上的代表位置——视频取 PRIMARY 文件，
 *                    图片取节点目录或压缩包本体。本地元数据文件就在它旁边找。
 *                    文件当前不可达时为 {@code null}。
 */
public record ScrapeSubject(
        LibraryDomain domain,
        Long targetId,
        Long libraryId,
        String title,
        Integer year,
        Path primaryPath) {
}
```

`src/main/java/com/mymedia/metadata/MetadataCandidate.java`：

```java
package com.mymedia.metadata;

/**
 * 一个提供者给出的候选匹配。
 *
 * @param score   置信度 0.0–1.0。高分自动应用，中分进待确认队列，
 *                <b>绝不在低置信度下强行写入</b>（spec 7.2 规则 4）。
 * @param payload 该候选的原始响应片段，原样存进 {@code scrape_candidate.payload}，
 *                用户确认时不必再查一次
 */
public record MetadataCandidate(
        String provider,
        String externalId,
        String title,
        Integer year,
        double score,
        String payload) {
}
```

`src/main/java/com/mymedia/metadata/ProviderUnavailableException.java`：

```java
package com.mymedia.metadata;

/**
 * 提供者暂时不可用：网络故障、超时、被限流。
 *
 * <p><b>与"没找到"严格区分。</b>没找到是正常状态（{@code NO_MATCH}，安静回落，
 * 界面不显示为错误）；不可用才置 {@code ERROR} 并按退避重试。把两者混为一谈
 * 会让一个冷门条目在任务表里永远重试下去。
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/mymedia/metadata/MetadataProvider.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;

import java.util.List;
import java.util.Optional;

/**
 * 元数据提供者。实现它并注册为 Spring bean 即可加入刮削链。
 *
 * <p>分成 {@code search} 与 {@code fetch} 两步而不是一步到位：搜索结果只够
 * 判断"像不像"，详情往往要再发一次请求。分开之后中等置信度的候选可以只存搜索
 * 结果、等用户确认时再取详情，<b>省掉一次注定要丢弃的详情请求</b>。
 *
 * <p>实现约定：
 * <ul>
 *   <li>找不到返回空列表，<b>不要抛异常</b>——没找到是正常状态。</li>
 *   <li>网络故障或被限流抛 {@link ProviderUnavailableException}。</li>
 *   <li>{@code score} 自己算（本项目用 {@code TitleSimilarity} 的二元组 Dice 系数）。</li>
 * </ul>
 */
public interface MetadataProvider {

    /** 提供者名，与 {@code libraries.metadata_providers} 里的字符串对应。 */
    String name();

    boolean supports(LibraryDomain domain);

    /**
     * 当前是否可用。默认可用；需要 API key 的提供者在缺 key 时返回 {@code false}，
     * 链会安静跳过它——<b>这不算错误</b>（spec 13 的风险缓解项）。
     */
    default boolean available() {
        return true;
    }

    List<MetadataCandidate> search(ScrapeSubject subject);

    /** 取详情。候选已失效（对方删了条目）时返回空。 */
    Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate);
}
```

- [ ] **Step 5: 写 NFO 解析**

`src/main/java/com/mymedia/metadata/ParsedNfo.java`：

```java
package com.mymedia.metadata;

import java.util.Map;

/** 本地元数据文件的解析结果。 */
record ParsedNfo(Map<String, String> fields, Map<String, String> extras) {
}
```

`src/main/java/com/mymedia/metadata/NfoParser.java`：

```java
package com.mymedia.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mymedia.shared.MetadataFields;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析本地元数据文件：Kodi / Jellyfin 的 {@code .nfo}（XML），
 * 以及本项目自己的 {@code metadata.json}。
 *
 * <p><b>纯逻辑，没有文件 I/O</b>：调用方读好字符串再喂进来，于是测试就是喂字符串。
 */
final class NfoParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** XML 标签 → 标准字段名。 */
    private static final Map<String, String> STANDARD_TAGS = Map.of(
            "title", MetadataFields.TITLE,
            "originaltitle", MetadataFields.ORIGINAL_TITLE,
            "plot", MetadataFields.SUMMARY,
            "premiered", MetadataFields.RELEASE_DATE,
            "rating", MetadataFields.RATING);

    /** XML 标签 → extras 键。 */
    private static final Map<String, String> EXTRA_TAGS = Map.of(
            "director", "director",
            "studio", "studio",
            "writer", "writer",
            "country", "country");

    private NfoParser() {
    }

    static ParsedNfo parseXml(String xml) {
        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();

        Map<String, String> fields = new LinkedHashMap<>();
        STANDARD_TAGS.forEach((tag, field) -> putIfPresent(fields, field, firstText(root, tag)));

        // premiered 缺席时退回 year，补成当年 1 月 1 日，让 release_date 列有个能排序的值
        if (!fields.containsKey(MetadataFields.RELEASE_DATE)) {
            String year = firstText(root, "year");
            if (year != null && year.matches("\\d{4}")) {
                fields.put(MetadataFields.RELEASE_DATE, year + "-01-01");
            }
        }

        Map<String, String> extras = new LinkedHashMap<>();
        EXTRA_TAGS.forEach((tag, key) -> putIfPresent(extras, key, firstText(root, tag)));
        List<String> genres = allText(root, "genre");
        if (!genres.isEmpty()) {
            extras.put("genres", String.join(", ", genres));
        }

        return new ParsedNfo(fields, extras);
    }

    static ParsedNfo parseJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 metadata.json", e);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        MetadataFields.STANDARD.forEach(field -> putIfPresent(fields, field, text(root.get(field))));

        Map<String, String> extras = new LinkedHashMap<>();
        JsonNode extrasNode = root.get("extras");
        if (extrasNode != null && extrasNode.isObject()) {
            extrasNode.properties().forEach(entry ->
                    putIfPresent(extras, entry.getKey(), text(entry.getValue())));
        }

        return new ParsedNfo(fields, extras);
    }

    /**
     * 建一个禁用 DTD 的解析器。
     *
     * <p>{@code .nfo} 是用户放在媒体目录里的文件，内容不可信。默认配置下一个
     * {@code <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>} 就能读走
     * 服务器上的任意文件。<b>关掉 DOCTYPE 是这里唯一正确的默认值</b>——
     * 合法的 .nfo 从来不需要 DTD。
     */
    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 .nfo：" + e.getMessage(), e);
        }
    }

    private static String firstText(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static List<String> allText(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String text = node.getTextContent();
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
```

- [ ] **Step 6: 写本地提供者**

`src/main/java/com/mymedia/metadata/LocalNfoProvider.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读同目录的 {@code .nfo}（Kodi / Jellyfin 标准）或 {@code metadata.json}。
 *
 * <p><b>它同时解决了演示数据的难题</b>：seed 数据全部靠本地文件提供元数据，
 * {@code docker compose up} 之后不需要任何 API key 就能看到完整的库
 * （spec 7.2 规则 6）。
 *
 * <p>本地文件是用户自己写的，没有"可能不对"这回事，因此 {@code score} 恒为 1.0，
 * 命中即被链自动应用。
 */
@Component
class LocalNfoProvider implements MetadataProvider {

    static final String NAME = "LocalNfo";

    private static final Logger log = LoggerFactory.getLogger(LocalNfoProvider.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return true;
    }

    @Override
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        return locate(subject)
                .map(file -> List.of(new MetadataCandidate(
                        NAME, file.getFileName().toString(), subject.title(),
                        subject.year(), 1.0, file.toString())))
                .orElseGet(List::of);
    }

    @Override
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<Path> file = locate(subject);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(file.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("本地元数据文件读取失败 {}", file.get(), e);
            return Optional.empty();
        }

        ParsedNfo parsed;
        try {
            parsed = file.get().getFileName().toString().endsWith(".json")
                    ? NfoParser.parseJson(content)
                    : NfoParser.parseXml(content);
        } catch (IllegalArgumentException e) {
            // 一个写坏的 .nfo 不该让整条链失败，安静跳过让后面的提供者接手
            log.warn("本地元数据文件格式有误，已跳过 {}：{}", file.get(), e.getMessage());
            return Optional.empty();
        }

        if (parsed.fields().isEmpty() && parsed.extras().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, file.get().getFileName().toString(),
                parsed.fields(), parsed.extras(), content));
    }

    /**
     * 按固定顺序找本地文件。
     *
     * <p>目标是文件时（视频）：先找与它同名的 {@code .nfo}，再找目录级的
     * {@code movie.nfo} / {@code tvshow.nfo} / {@code metadata.json}。
     * 目标是目录或压缩包时（图片）：在目录里找同一批名字。
     */
    private Optional<Path> locate(ScrapeSubject subject) {
        Path primary = subject.primaryPath();
        if (primary == null) {
            return Optional.empty();
        }

        boolean isDirectory = Files.isDirectory(primary);
        Path directory = isDirectory ? primary : primary.getParent();
        if (directory == null) {
            return Optional.empty();
        }

        List<Path> candidates = new ArrayList<>();
        String baseName = stripExtension(primary.getFileName().toString());
        candidates.add(directory.resolve(baseName + ".nfo"));
        candidates.add(directory.resolve("movie.nfo"));
        candidates.add(directory.resolve("tvshow.nfo"));
        candidates.add(directory.resolve("metadata.json"));

        return candidates.stream().filter(Files::isReadable).findFirst();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='NfoParserTest,LocalNfoProviderTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`NfoParserTest` 9 个、`LocalNfoProviderTest` 9 个用例通过。

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/metadata src/test/java/com/mymedia/metadata
git commit -m "feat: 添加 MetadataProvider SPI 与本地元数据文件提供者

search 与 fetch 分成两步：中等置信度的候选只存搜索结果，
等用户确认时再取详情，省掉一次注定要丢弃的请求。

XML 解析关掉 DOCTYPE：.nfo 是用户放在媒体目录里的不可信文件，
默认配置下一条外部实体声明就能读走服务器上的任意文件。

本地文件同时解决演示数据难题：seed 数据靠 NFO 提供元数据，
docker compose up 不需要任何 API key。"
```

---

## Task 9: 文件名兜底、标题相似度与提供者链

**Files:**
- Create: `src/main/java/com/mymedia/metadata/FilenameProvider.java`
- Create: `src/main/java/com/mymedia/metadata/TitleSimilarity.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataProperties.java`
- Create: `src/main/java/com/mymedia/metadata/ResolutionResult.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataResolver.java`
- Modify: `src/main/resources/application.yml`（追加 `mymedia.metadata` 配置块）
- Test: `src/test/java/com/mymedia/metadata/TitleSimilarityTest.java`
- Test: `src/test/java/com/mymedia/metadata/MetadataResolverTest.java`

**Interfaces:**
- Consumes: `MetadataProvider`、`ScrapeSubject`、`MetadataCandidate`、`ProviderUnavailableException`、`LocalNfoProvider`（Task 8）、`ScrapeStatus`、`MetadataPatch`（Task 7）
- Produces:
  - `class FilenameProvider implements MetadataProvider`（package-private）— `static final String NAME = "Filename"`
  - `class TitleSimilarity`（package-private）— `static double between(String a, String b)`
  - `record ResolutionResult(ScrapeStatus status, MetadataPatch patch, List<MetadataCandidate> candidates)`（package-private）
  - `class MetadataResolver`（package-private，Spring bean）— `ResolutionResult resolve(ScrapeSubject subject, List<String> configuredProviders)`

### 置信度用字符二元组的 Dice 系数

`2 × |A ∩ B| / (|A| + |B|)`，其中 A、B 是两个标题切出来的字符二元组多重集。

选它的三个理由：

1. **对中文有效**。编辑距离在中文上很不稳（"进击的巨人"与"巨人"编辑距离 3，看起来很远，实际是包含关系）；二元组重叠直接反映共有的字组。
2. **和 `pg_trgm` 正好是一组可对照的讲解素材**：搜索用的是数据库端的三元组索引，匹配用的是应用端的二元组系数，同一个思路的两种落点。中文二元组比三元组信息密度更合适（多数中文词是两字）。
3. **十几行就能写完，没有依赖**。

### 链的形态

```
LocalNfo  →  配置的外部刮削器（按 libraries.metadata_providers 的顺序）  →  Filename 兜底
```

- **`LocalNfo` 永远排在最前**，不需要写进配置：spec 7.2 规则 2 明确它优先于刮削。而规则 3「空数组即不刮削」由**任务层**兑现——空数组的库根本不排 `METADATA_FETCH`，于是 LocalNfo 也不会跑，界面零噪音。两条规则各自成立，不冲突。
- **`Filename` 永远排在最后**，且它的结果**不算命中**：应用它的同时状态置 `NO_MATCH`，界面安静回落，不显示为错误。
- 高分（≥ 0.8）**命中即停**，不再问后面的提供者。

- [ ] **Step 1: 写会失败的相似度单元测试**

`src/test/java/com/mymedia/metadata/TitleSimilarityTest.java`：

```java
package com.mymedia.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSimilarityTest {

    @Test
    void identicalTitlesScoreOne() {
        assertThat(TitleSimilarity.between("进击的巨人", "进击的巨人")).isEqualTo(1.0);
    }

    @Test
    void unrelatedChineseTitlesScoreZero() {
        assertThat(TitleSimilarity.between("进击的巨人", "夏目友人帐")).isZero();
    }

    @Test
    void aSubtitledSequelStillScoresHigh() {
        // 这正是需要人工确认的区间：像但不是同一个
        double score = TitleSimilarity.between("进击的巨人", "进击的巨人 最终季");
        assertThat(score).isBetween(0.4, 0.95);
    }

    @Test
    void substringRelationshipIsRecognisedUnlikeEditDistance() {
        // "巨人" 是 "进击的巨人" 的子串，编辑距离会给出很差的评价
        assertThat(TitleSimilarity.between("进击的巨人", "巨人")).isGreaterThan(0.3);
    }

    @Test
    void latinTitlesAreCompiledCaseInsensitively() {
        assertThat(TitleSimilarity.between("Big Buck Bunny", "big buck bunny")).isEqualTo(1.0);
    }

    @Test
    void whitespaceAndPunctuationDoNotAffectTheScore() {
        assertThat(TitleSimilarity.between("进击的巨人", "进击的·巨人 ")).isEqualTo(1.0);
    }

    @Test
    void emptyOrNullInputScoresZeroInsteadOfCrashing() {
        assertThat(TitleSimilarity.between("", "进击的巨人")).isZero();
        assertThat(TitleSimilarity.between(null, "进击的巨人")).isZero();
        assertThat(TitleSimilarity.between("进击的巨人", null)).isZero();
    }

    @Test
    void singleCharacterTitlesCompareByEquality() {
        // 一个字切不出二元组，退化成相等比较
        assertThat(TitleSimilarity.between("春", "春")).isEqualTo(1.0);
        assertThat(TitleSimilarity.between("春", "夏")).isZero();
    }

    @Test
    void repeatedCharactersAreCountedAsAMultisetNotASet() {
        // "AAAA" 与 "AA" 若按集合算会是 1.0，按多重集算应当低于 1
        assertThat(TitleSimilarity.between("AAAA", "AA")).isLessThan(1.0);
    }
}
```

- [ ] **Step 2: 写会失败的链编排测试**

`src/test/java/com/mymedia/metadata/MetadataResolverTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataResolverTest {

    /**
     * 手写替身，不用 Mockito：计划 01 把 Boot 4 的 test starter 拆开引入，
     * Mockito 是否在 classpath 上没有验证过。
     */
    private static final class FakeProvider implements MetadataProvider {

        private final String name;
        private final double score;
        private final boolean available;
        private final RuntimeException failure;
        final List<String> searchCalls = new ArrayList<>();

        FakeProvider(String name, double score) {
            this(name, score, true, null);
        }

        FakeProvider(String name, double score, boolean available, RuntimeException failure) {
            this.name = name;
            this.score = score;
            this.available = available;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(LibraryDomain domain) {
            return true;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<MetadataCandidate> search(ScrapeSubject subject) {
            searchCalls.add(subject.title());
            if (failure != null) {
                throw failure;
            }
            if (score <= 0) {
                return List.of();
            }
            return List.of(new MetadataCandidate(name, "id-" + name, name + " 的标题",
                    2019, score, "{}"));
        }

        @Override
        public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
            return Optional.of(new MetadataPatch(name, candidate.externalId(),
                    Map.of(MetadataFields.TITLE, candidate.title()), Map.of(), "{}"));
        }
    }

    private static final MetadataProperties PROPERTIES = new MetadataProperties(
            null, Duration.ZERO, 0.8, 0.4, null, null);

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "沙漠风暴", 2019, null);

    private static MetadataResolver resolverWith(MetadataProvider... providers) {
        List<MetadataProvider> all = new ArrayList<>(List.of(providers));
        all.add(new FakeProvider(FilenameProvider.NAME, 1.0));
        return new MetadataResolver(all, PROPERTIES);
    }

    @Test
    void highScoreIsAppliedAutomatically() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.95))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.MATCHED);
        assertThat(result.patch().source()).isEqualTo("TMDB");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void localNfoRunsFirstEvenWhenItIsNotInTheConfiguredList() {
        FakeProvider local = new FakeProvider(LocalNfoProvider.NAME, 1.0);
        FakeProvider tmdb = new FakeProvider("TMDB", 0.95);

        ResolutionResult result = resolverWith(local, tmdb).resolve(SUBJECT, List.of("TMDB"));

        // 本地文件优先于任何刮削（spec 7.2 规则 2），命中即停
        assertThat(result.patch().source()).isEqualTo(LocalNfoProvider.NAME);
        assertThat(tmdb.searchCalls).isEmpty();
    }

    @Test
    void configuredOrderDecidesWhichScraperGoesFirst() {
        FakeProvider tmdb = new FakeProvider("TMDB", 0.9);
        FakeProvider bangumi = new FakeProvider("Bangumi", 0.9);

        ResolutionResult result = resolverWith(tmdb, bangumi)
                .resolve(SUBJECT, List.of("Bangumi", "TMDB"));

        assertThat(result.patch().source()).isEqualTo("Bangumi");
        assertThat(tmdb.searchCalls).isEmpty();
    }

    @Test
    void mediumScoreGoesToTheReviewQueueAndWritesNothing() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.6))
                .resolve(SUBJECT, List.of("TMDB"));

        // 绝不在低置信度下强行写入（spec 7.2 规则 4）
        assertThat(result.status()).isEqualTo(ScrapeStatus.NEEDS_REVIEW);
        assertThat(result.patch()).isNull();
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void lowScoreIsDiscardedRatherThanQueuedForReview() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.2))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void noCandidatesFallsBackToFilenameQuietly() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.0))
                .resolve(SUBJECT, List.of("TMDB"));

        // 找不到是正常状态，不是错误
        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
        assertThat(result.patch().source()).isEqualTo(FilenameProvider.NAME);
    }

    @Test
    void unavailableProviderIsSkippedWithoutBeingAnError() {
        FakeProvider tmdb = new FakeProvider("TMDB", 0.95, false, null);

        ResolutionResult result = resolverWith(tmdb).resolve(SUBJECT, List.of("TMDB"));

        assertThat(tmdb.searchCalls).isEmpty();
        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
    }

    @Test
    void networkFailureWithNoOtherResultBecomesErrorSoTheJobRetries() {
        ResolutionResult result = resolverWith(new FakeProvider(
                "TMDB", 0.95, true, new ProviderUnavailableException("连接超时")))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.ERROR);
    }

    @Test
    void oneFlakyProviderDoesNotBlockAWorkingOne() {
        FakeProvider flaky = new FakeProvider(
                "TMDB", 0.95, true, new ProviderUnavailableException("429"));
        FakeProvider working = new FakeProvider("Bangumi", 0.9);

        ResolutionResult result = resolverWith(flaky, working)
                .resolve(SUBJECT, List.of("TMDB", "Bangumi"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.MATCHED);
        assertThat(result.patch().source()).isEqualTo("Bangumi");
    }

    @Test
    void unknownProviderNameInConfigIsIgnored() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.95))
                .resolve(SUBJECT, List.of("拼错了的名字"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='TitleSimilarityTest,MetadataResolverTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`TitleSimilarity`、`MetadataResolver` 等不存在。

- [ ] **Step 4: 实现相似度**

`src/main/java/com/mymedia/metadata/TitleSimilarity.java`：

```java
package com.mymedia.metadata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 标题相似度：字符二元组的 Dice 系数 {@code 2 × |A ∩ B| / (|A| + |B|)}。
 *
 * <p><b>为什么不是编辑距离</b>：编辑距离在中文上很不稳。"进击的巨人"与"巨人"
 * 编辑距离是 3，看起来很远，实际是包含关系；二元组重叠直接反映共有的字组。
 *
 * <p><b>为什么是二元组而不是三元组</b>：多数中文词是两个字，二元组的信息密度更合适。
 * 顺带一提，搜索走的是数据库端 {@code pg_trgm} 的三元组索引——同一个思路的两种落点，
 * 是一组现成的对照讲解素材。
 *
 * <p>交集按<b>多重集</b>算：{@code "AAAA"} 与 {@code "AA"} 若按集合算会得到 1.0，
 * 显然不对。
 */
final class TitleSimilarity {

    private TitleSimilarity() {
    }

    static double between(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.length() == 1 || b.length() == 1) {
            // 一个字切不出二元组，退化成相等比较
            return a.equals(b) ? 1.0 : 0.0;
        }

        Map<String, Integer> aGrams = bigrams(a);
        Map<String, Integer> bGrams = bigrams(b);

        int intersection = 0;
        for (Map.Entry<String, Integer> entry : aGrams.entrySet()) {
            intersection += Math.min(entry.getValue(), bGrams.getOrDefault(entry.getKey(), 0));
        }
        return 2.0 * intersection / (a.length() - 1 + b.length() - 1);
    }

    /** 去掉空白与标点、统一小写——它们不携带辨识信息，只会稀释系数。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(codePoint);
            }
        });
        return normalized.toString();
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i < value.length() - 1; i++) {
            grams.merge(value.substring(i, i + 2), 1, Integer::sum);
        }
        return grams;
    }
}
```

- [ ] **Step 5: 实现文件名兜底提供者**

`src/main/java/com/mymedia/metadata/FilenameProvider.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 兜底提供者：永远成功，把已有的标题稍作清理后交出去。
 *
 * <p>它的存在让 spec 7.2 规则 1「无刮削亦完全可用」成立——链的末端总有结果，
 * 条目不会停在"什么都没有"的状态。
 *
 * <p><b>它的结果不算命中</b>：链在应用它的同时把状态置为 {@code NO_MATCH}，
 * 界面安静回落，不显示为错误。
 */
@Component
class FilenameProvider implements MetadataProvider {

    static final String NAME = "Filename";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return true;
    }

    @Override
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        return List.of(new MetadataCandidate(NAME, null, clean(subject.title()),
                subject.year(), 1.0, "{}"));
    }

    @Override
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Map<String, String> fields = new LinkedHashMap<>();
        String title = clean(subject.title());
        if (title != null && !title.isBlank()) {
            fields.put(MetadataFields.TITLE, title);
        }
        if (subject.year() != null) {
            fields.put(MetadataFields.RELEASE_DATE, subject.year() + "-01-01");
        }
        return fields.isEmpty() ? Optional.empty()
                : Optional.of(new MetadataPatch(NAME, null, fields, Map.of(), null));
    }

    /**
     * 去掉发布组方括号、把点和下划线还原成空格、收敛连续空白。
     *
     * <p>比计划 03 的 {@code VideoFilenameParser} 轻得多——那个解析器要认季集号，
     * 这里只需要一个能看的标题。两者不共用代码是有意的：解析器是 {@code video}
     * 模块的内部实现，把它开放出来只为了做字符串清理，代价大于收益。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\[[^\\]]*\\]", " ")
                  .replaceAll("[._]+", " ")
                  .replaceAll("\\s+", " ")
                  .trim();
    }
}
```

- [ ] **Step 6: 实现配置与链编排**

`src/main/java/com/mymedia/metadata/MetadataProperties.java`：

```java
package com.mymedia.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 刮削相关配置。
 *
 * @param userAgent          外部请求必须带的标识性 UA——匿名爬对方是给自己招风控
 * @param minRequestInterval 客户端侧限流：同一个提供者两次请求之间的最小间隔
 * @param autoApplyThreshold 相似度达到它就自动应用
 * @param reviewThreshold    相似度达到它就进待确认队列，低于它直接丢弃
 */
@ConfigurationProperties(prefix = "mymedia.metadata")
record MetadataProperties(
        String userAgent,
        Duration minRequestInterval,
        double autoApplyThreshold,
        double reviewThreshold,
        Bangumi bangumi,
        Tmdb tmdb) {

    record Bangumi(String baseUrl) {
        Bangumi {
            baseUrl = baseUrl == null ? "https://api.bgm.tv" : baseUrl;
        }
    }

    record Tmdb(String baseUrl, String apiKey, String language) {
        Tmdb {
            baseUrl = baseUrl == null ? "https://api.themoviedb.org/3" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            language = language == null ? "zh-CN" : language;
        }
    }

    MetadataProperties {
        userAgent = userAgent == null || userAgent.isBlank()
                ? "MyMedia/0.1 (self-hosted media library)" : userAgent;
        minRequestInterval = minRequestInterval == null ? Duration.ofSeconds(1) : minRequestInterval;
        autoApplyThreshold = autoApplyThreshold <= 0 ? 0.8 : autoApplyThreshold;
        reviewThreshold = reviewThreshold <= 0 ? 0.4 : reviewThreshold;
        bangumi = bangumi == null ? new Bangumi(null) : bangumi;
        tmdb = tmdb == null ? new Tmdb(null, null, null) : tmdb;
    }
}
```

`src/main/java/com/mymedia/metadata/ResolutionResult.java`：

```java
package com.mymedia.metadata;

import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;

import java.util.List;

/**
 * 一次刮削的结论。
 *
 * @param status     写回条目的 {@code scrape_status}
 * @param patch      要应用的数据；{@code NEEDS_REVIEW} 与 {@code ERROR} 时为 {@code null}
 * @param candidates 要写进 {@code scrape_candidate} 的候选；只有 {@code NEEDS_REVIEW} 时非空
 */
record ResolutionResult(ScrapeStatus status, MetadataPatch patch, List<MetadataCandidate> candidates) {

    static ResolutionResult matched(MetadataPatch patch) {
        return new ResolutionResult(ScrapeStatus.MATCHED, patch, List.of());
    }

    static ResolutionResult needsReview(List<MetadataCandidate> candidates) {
        return new ResolutionResult(ScrapeStatus.NEEDS_REVIEW, null, candidates);
    }

    static ResolutionResult noMatch(MetadataPatch fallbackPatch) {
        return new ResolutionResult(ScrapeStatus.NO_MATCH, fallbackPatch, List.of());
    }

    static ResolutionResult error() {
        return new ResolutionResult(ScrapeStatus.ERROR, null, List.of());
    }
}
```

`src/main/java/com/mymedia/metadata/MetadataResolver.java`：

```java
package com.mymedia.metadata;

import com.mymedia.shared.MetadataPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供者链的编排。
 *
 * <pre>
 * LocalNfo  →  配置的外部刮削器（按 libraries.metadata_providers 的顺序）  →  Filename 兜底
 * </pre>
 *
 * <p><b>链的顺序就是 spec 7.2 的优先级</b>，不需要第二套 tier 比较：
 * <ul>
 *   <li>{@code LocalNfo} 永远排最前，不必写进配置——规则 2 明确它优先于刮削。
 *       规则 3「空数组即不刮削」由任务层兑现：那样的库根本不排任务，
 *       LocalNfo 也就不会跑。</li>
 *   <li>高分（≥ {@code autoApplyThreshold}）<b>命中即停</b>，后面的提供者不再问。</li>
 *   <li>{@code Filename} 永远排最后，且它的结果<b>不算命中</b>：
 *       应用的同时置 {@code NO_MATCH}，界面安静回落。</li>
 * </ul>
 *
 * <p>一个提供者抛 {@link ProviderUnavailableException} 不会中断整条链：先记下来接着问
 * 后面的。只有<b>什么都没得到且确实发生过故障</b>时才判 {@code ERROR} 让任务重试——
 * 否则一个限流中的 TMDB 会连带毁掉本来能命中的 Bangumi。
 */
@Component
class MetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataResolver.class);

    private final Map<String, MetadataProvider> providersByName;
    private final MetadataProperties properties;

    MetadataResolver(List<MetadataProvider> providers, MetadataProperties properties) {
        Map<String, MetadataProvider> byName = new LinkedHashMap<>();
        providers.forEach(provider -> byName.put(provider.name(), provider));
        this.providersByName = byName;
        this.properties = properties;
    }

    ResolutionResult resolve(ScrapeSubject subject, List<String> configuredProviders) {
        List<MetadataCandidate> reviewable = new ArrayList<>();
        boolean sawFailure = false;

        for (MetadataProvider provider : chainFor(subject, configuredProviders)) {
            List<MetadataCandidate> candidates;
            try {
                candidates = provider.search(subject);
            } catch (ProviderUnavailableException e) {
                log.warn("提供者 {} 暂时不可用：{}", provider.name(), e.getMessage());
                sawFailure = true;
                continue;
            }

            Optional<MetadataCandidate> best = candidates.stream()
                    .max(Comparator.comparingDouble(MetadataCandidate::score));
            if (best.isEmpty()) {
                continue;
            }

            MetadataCandidate candidate = best.get();
            if (candidate.score() >= properties.autoApplyThreshold()) {
                try {
                    Optional<MetadataPatch> patch = provider.fetch(subject, candidate);
                    if (patch.isPresent()) {
                        return ResolutionResult.matched(patch.get());
                    }
                } catch (ProviderUnavailableException e) {
                    log.warn("提供者 {} 取详情失败：{}", provider.name(), e.getMessage());
                    sawFailure = true;
                }
                continue;
            }

            if (candidate.score() >= properties.reviewThreshold()) {
                // 绝不在低置信度下强行写入：攒起来交给用户确认
                candidates.stream()
                        .filter(each -> each.score() >= properties.reviewThreshold())
                        .forEach(reviewable::add);
            }
        }

        if (!reviewable.isEmpty()) {
            return ResolutionResult.needsReview(reviewable);
        }
        if (sawFailure) {
            // 什么都没拿到，而且确实有提供者报了故障 —— 值得重试
            return ResolutionResult.error();
        }
        return ResolutionResult.noMatch(fallback(subject));
    }

    /** 构造这次要问的提供者序列：LocalNfo 打头，配置的刮削器居中，Filename 不在其中。 */
    private List<MetadataProvider> chainFor(ScrapeSubject subject, List<String> configuredProviders) {
        List<MetadataProvider> chain = new ArrayList<>();
        addIfUsable(chain, providersByName.get(LocalNfoProvider.NAME), subject);
        for (String name : configuredProviders) {
            if (LocalNfoProvider.NAME.equals(name) || FilenameProvider.NAME.equals(name)) {
                continue;   // 这两个的位置是固定的，配置里写了也不改变顺序
            }
            MetadataProvider provider = providersByName.get(name);
            if (provider == null) {
                log.warn("配置里的刮削器不存在，已忽略: {}", name);
                continue;
            }
            addIfUsable(chain, provider, subject);
        }
        return chain;
    }

    private void addIfUsable(List<MetadataProvider> chain, MetadataProvider provider,
                             ScrapeSubject subject) {
        if (provider == null) {
            return;
        }
        if (!provider.supports(subject.domain())) {
            return;
        }
        if (!provider.available()) {
            // 缺 API key 之类：安静跳过，不算错误
            log.debug("提供者 {} 当前不可用，已跳过", provider.name());
            return;
        }
        chain.add(provider);
    }

    private MetadataPatch fallback(ScrapeSubject subject) {
        MetadataProvider filename = providersByName.get(FilenameProvider.NAME);
        if (filename == null) {
            return null;
        }
        return filename.search(subject).stream().findFirst()
                .flatMap(candidate -> filename.fetch(subject, candidate))
                .orElse(null);
    }
}
```

- [ ] **Step 7: 追加配置**

在 `src/main/resources/application.yml` 的 `mymedia:` 块下追加：

```yaml
  metadata:
    user-agent: "MyMedia/0.1 (https://github.com/kisima0225/MyMedia)"   # 匿名爬对方是给自己招风控
    min-request-interval: PT1S      # 客户端侧限流
    auto-apply-threshold: 0.8       # 相似度达到就自动应用
    review-threshold: 0.4           # 达到就进待确认队列，低于此直接丢弃
    bangumi:
      base-url: https://api.bgm.tv  # 无需鉴权（已实测）
    tmdb:
      base-url: https://api.themoviedb.org/3
      api-key: ""                   # 留空 → TmdbProvider 报告不可用，链跳过它，不算错误
      language: zh-CN
```

- [ ] **Step 8: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='TitleSimilarityTest,MetadataResolverTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`TitleSimilarityTest` 9 个、`MetadataResolverTest` 10 个用例通过。

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/metadata src/main/resources/application.yml src/test/java/com/mymedia/metadata
git commit -m "feat: 添加文件名兜底、二元组相似度与提供者链编排

链的顺序就是 spec 7.2 的优先级，不需要第二套 tier 比较：
LocalNfo 永远在前，Filename 永远在后且不算命中（置 NO_MATCH 安静回落）。

置信度用字符二元组的 Dice 系数：编辑距离在中文上很不稳，
而它和搜索用的 pg_trgm 三元组正好是同一思路的两种落点。

一个提供者故障不中断整条链——只有什么都没拿到且确实发生过故障
才判 ERROR，否则限流中的 TMDB 会连带毁掉能命中的 Bangumi。"
```

---

## Task 10: 外部提供者（Bangumi 与 TMDB）

**Files:**
- Create: `src/main/java/com/mymedia/metadata/ProviderCacheConfig.java`
- Create: `src/main/java/com/mymedia/metadata/HttpProviderSupport.java`
- Create: `src/main/java/com/mymedia/metadata/BangumiProvider.java`
- Create: `src/main/java/com/mymedia/metadata/TmdbProvider.java`
- Test: `src/test/java/com/mymedia/metadata/StubHttpServer.java`
- Test: `src/test/java/com/mymedia/metadata/BangumiProviderTest.java`
- Test: `src/test/java/com/mymedia/metadata/TmdbProviderTest.java`

**Interfaces:**
- Consumes: `MetadataProvider`、`ScrapeSubject`、`MetadataCandidate`、`ProviderUnavailableException`、`TitleSimilarity`、`MetadataProperties`（Task 8、9）
- Produces:
  - `class HttpProviderSupport`（package-private，Spring bean）
    - `RestClient client(String baseUrl)`
    - `Optional<String> get(String provider, RestClient client, String uriTemplate, Object... uriVariables)`
    - `Optional<String> postJson(String provider, RestClient client, String uriTemplate, String body, Object... uriVariables)`
  - `class BangumiProvider implements MetadataProvider`（package-private）— `static final String NAME = "Bangumi"`
  - `class TmdbProvider implements MetadataProvider`（package-private）— `static final String NAME = "TMDB"`

### 已实测的 Bangumi 协议事实（不要凭记忆改）

| 事实 | 验证方式 |
|---|---|
| 无需鉴权：`GET /v0/subjects/{id}` → 200 | 实跑 |
| 搜索：`POST /v0/search/subjects?limit=N`，体 `{"keyword":"…","filter":{"type":[2]}}` → 200 | 实跑，`进击的巨人` 命中 32 条 |
| 搜索结果字段：`id, name, name_cn, date, rating.score, image, images, eps, infobox, meta_tags, platform, collection, locked, nsfw`（**没有 summary**） | 实跑 |
| 详情多出：`summary, tags, type, total_episodes, volumes, series` | 实跑 `/v0/subjects/55770` |
| `type`：1=书籍 2=动画 3=音乐 4=游戏 6=三次元 | 实跑 |

**搜索结果没有 `summary` 正是 `search` / `fetch` 分成两步的现实依据**：想要简介就必须再请求一次详情，那不如把这次请求推迟到确定要用它的时候。

### TMDB 的协议形状未实测

本机没有 TMDB API key，因此 `TmdbProvider` 的端点与字段名**按官方文档写、由本地桩服务器覆盖**，没有真机验证。这一点必须在讲解文档里写明，不要在简历上说"对接并验证了 TMDB"。

真正需要保证的是**缺 key 时优雅降级**——那条路径有测试，且不依赖任何外部服务。

### 三条外部调用的纪律

1. **带标识性 `User-Agent`**：匿名爬对方是给自己招风控。Bangumi 的文档明确要求。
2. **客户端侧限流**：同一提供者两次请求之间至少隔 `min-request-interval`。别指望对方的 429 来教你做人。
3. **结果缓存**：一次扫描里同名条目会重复查询，缓存直接省掉重复请求。用 Spring 自带的 `ConcurrentMapCacheManager`，**不引 Redis 也不引 Caffeine**——单实例、无跨节点需求，引入无法解释的中间件在面试中是负分。缓存键是 (标题, 年份)，条目数上界就是媒体库的条目数，不会失控。

- [ ] **Step 1: 写桩 HTTP 服务器**

`src/test/java/com/mymedia/metadata/StubHttpServer.java`：

```java
package com.mymedia.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的本地 HTTP 桩。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver}（JDK 25 下可从 classpath 直接用，已实测），
 * <b>不引 MockWebServer</b>——为了几个测试多一个依赖说不出理由；也<b>不赌
 * {@code MockRestServiceServer} 对 {@code RestClient} 的支持</b>，那是没验证过的事。
 *
 * <p>它是真的在监听端口、真的走 HTTP，因此连 User-Agent 头有没有发出去都能断言。
 */
class StubHttpServer implements AutoCloseable {

    private record Canned(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Canned> responses = new HashMap<>();
    private final List<String> requestedUris = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final Map<String, String> lastHeaders = new HashMap<>();

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubHttpServer stub = new StubHttpServer(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动桩 HTTP 服务器", e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 注册一条应答。{@code path} 只比对路径部分，不含查询串。 */
    void respond(String path, int status, String body) {
        responses.put(path, new Canned(status, body));
    }

    List<String> requestedUris() {
        return List.copyOf(requestedUris);
    }

    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    String lastHeader(String name) {
        return lastHeaders.get(name.toLowerCase());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestedUris.add(exchange.getRequestURI().toString());
        exchange.getRequestHeaders().forEach((name, values) ->
                lastHeaders.put(name.toLowerCase(), String.join(",", values)));
        try (InputStream in = exchange.getRequestBody()) {
            requestBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        Canned canned = responses.getOrDefault(exchange.getRequestURI().getPath(),
                new Canned(404, "{\"detail\":\"not found\"}"));
        byte[] body = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(canned.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: 写会失败的 Bangumi 测试**

`src/test/java/com/mymedia/metadata/BangumiProviderTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BangumiProviderTest {

    /** 字段取自实跑 POST /v0/search/subjects 的真实响应形状——注意没有 summary。 */
    private static final String SEARCH_BODY = """
            {
              "total": 2,
              "data": [
                {"id": 55770, "name": "進撃の巨人", "name_cn": "进击的巨人",
                 "date": "2013-04-07", "rating": {"score": 8.4}, "platform": "TV"},
                {"id": 12345, "name": "Natsume Yuujinchou", "name_cn": "夏目友人帐",
                 "date": "2008-07-08", "rating": {"score": 8.8}, "platform": "TV"}
              ]
            }
            """;

    /** 详情才有 summary。 */
    private static final String DETAIL_BODY = """
            {
              "id": 55770, "name": "進撃の巨人", "name_cn": "进击的巨人",
              "date": "2013-04-07", "summary": "人类居住在高墙之内。",
              "rating": {"score": 8.4}, "type": 2, "total_episodes": 25,
              "platform": "TV"
            }
            """;

    private StubHttpServer server;
    private BangumiProvider provider;

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "进击的巨人", 2013, null);

    @BeforeEach
    void startServer() {
        server = StubHttpServer.start();
        MetadataProperties properties = new MetadataProperties(
                "MyMediaTest/0.1", Duration.ZERO, 0.8, 0.4,
                new MetadataProperties.Bangumi(server.baseUrl()), null);
        provider = new BangumiProvider(new HttpProviderSupport(properties), properties);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void searchesByPostingKeywordAndTypeFilter() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        provider.search(SUBJECT);

        assertThat(server.requestedUris().get(0)).startsWith("/v0/search/subjects?limit=");
        assertThat(server.requestBodies().get(0))
                .contains("\"keyword\":\"进击的巨人\"")
                .contains("\"type\":[2]");
    }

    @Test
    void sendsAnIdentifyingUserAgent() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        provider.search(SUBJECT);

        // 匿名爬对方是给自己招风控，Bangumi 的文档明确要求带 UA
        assertThat(server.lastHeader("User-Agent")).isEqualTo("MyMediaTest/0.1");
    }

    @Test
    void scoresCandidatesByTitleSimilarity() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        List<MetadataCandidate> candidates = provider.search(SUBJECT);

        assertThat(candidates).hasSize(2);
        MetadataCandidate first = candidates.stream()
                .filter(candidate -> "55770".equals(candidate.externalId())).findFirst().orElseThrow();
        MetadataCandidate second = candidates.stream()
                .filter(candidate -> "12345".equals(candidate.externalId())).findFirst().orElseThrow();
        assertThat(first.score()).isEqualTo(1.0);
        assertThat(second.score()).isLessThan(0.4);
        assertThat(first.title()).isEqualTo("进击的巨人");
        assertThat(first.year()).isEqualTo(2013);
    }

    @Test
    void matchesAgainstOriginalNameToo() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);
        ScrapeSubject japanese = new ScrapeSubject(
                LibraryDomain.VIDEO, 1L, 1L, "進撃の巨人", null, null);

        // 日文原名与中译名都要参与比对，取较高者——文件名两种写法都常见
        assertThat(provider.search(japanese).stream()
                .filter(candidate -> "55770".equals(candidate.externalId()))
                .findFirst().orElseThrow().score()).isEqualTo(1.0);
    }

    @Test
    void emptyResultIsAnEmptyListNotAnException() {
        server.respond("/v0/search/subjects", 200, "{\"total\":0,\"data\":[]}");

        assertThat(provider.search(SUBJECT)).isEmpty();
    }

    @Test
    void fetchesSummaryFromTheDetailEndpoint() {
        server.respond("/v0/subjects/55770", 200, DETAIL_BODY);

        Optional<MetadataPatch> patch = provider.fetch(SUBJECT, new MetadataCandidate(
                BangumiProvider.NAME, "55770", "进击的巨人", 2013, 1.0, "{}"));

        assertThat(patch).isPresent();
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "进击的巨人")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "進撃の巨人")
                .containsEntry(MetadataFields.SUMMARY, "人类居住在高墙之内。")
                .containsEntry(MetadataFields.RELEASE_DATE, "2013-04-07")
                .containsEntry(MetadataFields.RATING, "8.4");
        assertThat(patch.get().rawResponse()).contains("total_episodes");
    }

    @Test
    void deletedSubjectIsEmptyRatherThanAnError() {
        // 桩默认对未注册路径返回 404
        assertThat(provider.fetch(SUBJECT, new MetadataCandidate(
                BangumiProvider.NAME, "999999", "没了", null, 1.0, "{}"))).isEmpty();
    }

    @Test
    void rateLimitingResponseBecomesProviderUnavailable() {
        server.respond("/v0/search/subjects", 429, "{\"detail\":\"too many requests\"}");

        // 被限流要重试，不能当成"没找到"
        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void serverErrorBecomesProviderUnavailable() {
        server.respond("/v0/search/subjects", 500, "{}");

        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void connectionRefusedBecomesProviderUnavailable() {
        server.close();

        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void usesBookTypeFilterForImageLibraries() {
        server.respond("/v0/search/subjects", 200, "{\"total\":0,\"data\":[]}");

        provider.search(new ScrapeSubject(LibraryDomain.IMAGE, 2L, 1L, "某漫画", null, null));

        // type 1 = 书籍（漫画在 Bangumi 属于书籍）
        assertThat(server.requestBodies().get(0)).contains("\"type\":[1]");
    }

    @Test
    void supportsBothDomainsAndNeedsNoApiKey() {
        assertThat(provider.supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(provider.supports(LibraryDomain.IMAGE)).isTrue();
        assertThat(provider.available()).isTrue();
    }
}
```

- [ ] **Step 3: 写会失败的 TMDB 测试**

`src/test/java/com/mymedia/metadata/TmdbProviderTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbProviderTest {

    private static final String SEARCH_BODY = """
            {
              "page": 1,
              "results": [
                {"id": 10378, "title": "大雄兔", "original_title": "Big Buck Bunny",
                 "release_date": "2008-05-20", "vote_average": 7.9,
                 "overview": "一只巨兔与三个坏蛋的故事。"}
              ]
            }
            """;

    private static final String DETAIL_BODY = """
            {
              "id": 10378, "title": "大雄兔", "original_title": "Big Buck Bunny",
              "release_date": "2008-05-20", "vote_average": 7.9,
              "overview": "一只巨兔与三个坏蛋的故事。",
              "production_companies": [{"name": "Blender Foundation"}]
            }
            """;

    private StubHttpServer server;

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "大雄兔", 2008, null);

    private TmdbProvider providerWithKey(String apiKey) {
        MetadataProperties properties = new MetadataProperties(
                "MyMediaTest/0.1", Duration.ZERO, 0.8, 0.4, null,
                new MetadataProperties.Tmdb(server.baseUrl(), apiKey, "zh-CN"));
        return new TmdbProvider(new HttpProviderSupport(properties), properties);
    }

    @BeforeEach
    void startServer() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void reportsUnavailableWhenNoApiKeyIsConfigured() {
        // 别人克隆这个仓库时没有 key，链必须安静跳过而不是报错（spec 13 的风险缓解项）
        assertThat(providerWithKey("").available()).isFalse();
        assertThat(providerWithKey("   ").available()).isFalse();
        assertThat(providerWithKey("real-key").available()).isTrue();
    }

    @Test
    void onlySupportsVideoLibraries() {
        // TMDB 管影视，漫画与图集归 Bangumi
        assertThat(providerWithKey("k").supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(providerWithKey("k").supports(LibraryDomain.IMAGE)).isFalse();
    }

    @Test
    void searchSendsApiKeyLanguageQueryAndYear() {
        server.respond("/search/movie", 200, SEARCH_BODY);

        providerWithKey("real-key").search(SUBJECT);

        String uri = server.requestedUris().get(0);
        assertThat(uri).startsWith("/search/movie?");
        assertThat(uri).contains("api_key=real-key");
        assertThat(uri).contains("language=zh-CN");
        assertThat(uri).contains("year=2008");
    }

    @Test
    void mapsSearchResultsToScoredCandidates() {
        server.respond("/search/movie", 200, SEARCH_BODY);

        List<MetadataCandidate> candidates = providerWithKey("k").search(SUBJECT);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).externalId()).isEqualTo("10378");
        assertThat(candidates.get(0).year()).isEqualTo(2008);
        assertThat(candidates.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void fetchMapsDetailFieldsIncludingStudio() {
        server.respond("/movie/10378", 200, DETAIL_BODY);

        Optional<MetadataPatch> patch = providerWithKey("k").fetch(SUBJECT,
                new MetadataCandidate(TmdbProvider.NAME, "10378", "大雄兔", 2008, 1.0, "{}"));

        assertThat(patch).isPresent();
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "Big Buck Bunny")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20")
                .containsEntry(MetadataFields.RATING, "7.9");
        assertThat(patch.get().extras()).containsEntry("studio", "Blender Foundation");
    }

    @Test
    void unauthorizedKeyBecomesProviderUnavailable() {
        server.respond("/search/movie", 401, "{\"status_message\":\"Invalid API key\"}");

        // key 配错了要能看出来，而不是静静地一个条目都刮不出来
        assertThatThrownBy(() -> providerWithKey("wrong").search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void emptyResultsAreNotAnError() {
        server.respond("/search/movie", 200, "{\"page\":1,\"results\":[]}");

        assertThat(providerWithKey("k").search(SUBJECT)).isEmpty();
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='BangumiProviderTest,TmdbProviderTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`HttpProviderSupport`、`BangumiProvider`、`TmdbProvider` 不存在。

- [ ] **Step 5: 写 HTTP 支撑与缓存配置**

`src/main/java/com/mymedia/metadata/HttpProviderSupport.java`：

```java
package com.mymedia.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 HTTP 调用的公共部分：标识性 UA、客户端侧限流、状态码到语义的映射。
 *
 * <p><b>状态码怎么翻译成语义，是这个类存在的主要理由：</b>
 * <ul>
 *   <li>2xx → 有结果</li>
 *   <li>404 → <b>空</b>。对方删了条目属于"没找到"，是正常状态。</li>
 *   <li>其余（401 / 429 / 5xx / 连不上）→ {@link ProviderUnavailableException}，
 *       条目置 {@code ERROR} 并按退避重试。</li>
 * </ul>
 * 把 404 和 429 混为一谈，会让一个冷门条目在任务表里永远重试下去。
 */
@Component
class HttpProviderSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderSupport.class);

    private final MetadataProperties properties;

    /** 每个提供者一把闸，记录它上一次请求的时刻。 */
    private final Map<String, Object> gates = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallMillis = new ConcurrentHashMap<>();

    HttpProviderSupport(MetadataProperties properties) {
        this.properties = properties;
    }

    RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // 匿名爬对方是给自己招风控；Bangumi 的文档明确要求带标识性 UA
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    Optional<String> get(String provider, RestClient client, String uriTemplate,
                         Object... uriVariables) {
        throttle(provider);
        return interpret(provider, () -> client.get()
                .uri(uriTemplate, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                    // 状态码由下面统一判断，这里只是关掉 RestClient 的默认抛异常行为
                })
                .toEntity(String.class));
    }

    Optional<String> postJson(String provider, RestClient client, String uriTemplate,
                              String body, Object... uriVariables) {
        throttle(provider);
        return interpret(provider, () -> client.post()
                .uri(uriTemplate, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class));
    }

    private Optional<String> interpret(String provider,
                                       java.util.function.Supplier<ResponseEntity<String>> call) {
        ResponseEntity<String> response;
        try {
            response = call.get();
        } catch (RestClientException e) {
            // 连不上、超时、读到一半断了
            throw new ProviderUnavailableException(provider + " 请求失败: " + e.getMessage(), e);
        }

        int status = response.getStatusCode().value();
        if (response.getStatusCode().is2xxSuccessful()) {
            return Optional.ofNullable(response.getBody());
        }
        if (status == 404) {
            // 对方删了条目：这是"没找到"，不是故障
            return Optional.empty();
        }
        throw new ProviderUnavailableException(provider + " 返回 HTTP " + status);
    }

    /**
     * 客户端侧限流：同一提供者两次请求之间至少隔 {@code min-request-interval}。
     *
     * <p>别指望对方的 429 来教你做人——等到被限流时，这一轮扫描的几百个请求
     * 已经发出去了。
     */
    private void throttle(String provider) {
        long minIntervalMillis = properties.minRequestInterval().toMillis();
        if (minIntervalMillis <= 0) {
            return;
        }
        Object gate = gates.computeIfAbsent(provider, key -> new Object());
        synchronized (gate) {
            long now = System.currentTimeMillis();
            long earliest = lastCallMillis.getOrDefault(provider, 0L) + minIntervalMillis;
            if (now < earliest) {
                try {
                    Thread.sleep(earliest - now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderUnavailableException(provider + " 限流等待被中断", e);
                }
            }
            lastCallMillis.put(provider, System.currentTimeMillis());
        }
        log.trace("{} 通过限流闸", provider);
    }
}
```

`src/main/java/com/mymedia/metadata/ProviderCacheConfig.java`：

```java
package com.mymedia.metadata;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 刮削结果缓存。
 *
 * <p>用 Spring 自带的 {@link ConcurrentMapCacheManager}，<b>不引 Redis 也不引 Caffeine</b>：
 * 单实例部署、没有跨节点缓存需求，引入一个无法解释的中间件在面试里是负分。
 *
 * <p>它没有淘汰策略，这是可以接受的：缓存键是 (提供者, 标题, 年份)，
 * 条目数上界就是媒体库的条目数——一个一万条目的库也就一万个几百字节的条目。
 * 真正的收益是一轮扫描里同名条目（同一部剧的多集、同一画师的多个合集）
 * 只查一次。
 */
@Configuration
@EnableCaching
class ProviderCacheConfig {

    static final String SEARCH_CACHE = "metadataSearch";
    static final String DETAIL_CACHE = "metadataDetail";

    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(SEARCH_CACHE, DETAIL_CACHE);
    }
}
```

- [ ] **Step 6: 写 Bangumi 提供者**

`src/main/java/com/mymedia/metadata/BangumiProvider.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bangumi（bgm.tv）提供者：番剧走视频域，漫画走图片域。
 *
 * <p>协议事实全部实测过（见本任务的说明表）：无需鉴权；搜索是
 * {@code POST /v0/search/subjects}；<b>搜索结果里没有 summary</b>，
 * 简介只在详情里有——这正是 SPI 把 {@code search} 与 {@code fetch} 分成两步的现实依据。
 */
@Component
class BangumiProvider implements MetadataProvider {

    static final String NAME = "Bangumi";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SEARCH_LIMIT = 10;

    /** Bangumi 的条目类型：1=书籍 2=动画 3=音乐 4=游戏 6=三次元（实测）。 */
    private static final int TYPE_BOOK = 1;
    private static final int TYPE_ANIME = 2;

    private final HttpProviderSupport http;
    private final RestClient client;

    BangumiProvider(HttpProviderSupport http, MetadataProperties properties) {
        this.http = http;
        this.client = http.client(properties.bangumi().baseUrl());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return true;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.SEARCH_CACHE,
               key = "'bangumi:' + #subject.domain() + ':' + #subject.title()")
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        int type = subject.domain() == LibraryDomain.IMAGE ? TYPE_BOOK : TYPE_ANIME;
        String body = "{\"keyword\":" + quote(subject.title())
                + ",\"filter\":{\"type\":[" + type + "]}}";

        Optional<String> response = http.postJson(NAME, client,
                "/v0/search/subjects?limit={limit}", body, SEARCH_LIMIT);
        if (response.isEmpty()) {
            return List.of();
        }

        JsonNode root = parse(response.get());
        List<MetadataCandidate> candidates = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            candidates.add(toCandidate(subject, item));
        }
        return candidates;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.DETAIL_CACHE,
               key = "'bangumi:' + #candidate.externalId()")
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<String> response = http.get(NAME, client,
                "/v0/subjects/{id}", candidate.externalId());
        if (response.isEmpty()) {
            return Optional.empty();
        }

        JsonNode item = parse(response.get());
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, MetadataFields.TITLE, displayName(item));
        putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, item.path("name").asString(null));
        putIfPresent(fields, MetadataFields.SUMMARY, item.path("summary").asString(null));
        putIfPresent(fields, MetadataFields.RELEASE_DATE, item.path("date").asString(null));
        putIfPresent(fields, MetadataFields.RATING, item.path("rating").path("score").asString(null));

        Map<String, String> extras = new LinkedHashMap<>();
        putIfPresent(extras, "platform", item.path("platform").asString(null));
        putIfPresent(extras, "totalEpisodes", item.path("total_episodes").asString(null));
        putIfPresent(extras, "volumes", item.path("volumes").asString(null));

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, candidate.externalId(),
                fields, extras, response.get()));
    }

    private MetadataCandidate toCandidate(ScrapeSubject subject, JsonNode item) {
        String chinese = item.path("name_cn").asString(null);
        String original = item.path("name").asString(null);
        // 中译名与原名都比一遍取较高者——文件名两种写法都常见
        double score = Math.max(
                TitleSimilarity.between(subject.title(), chinese),
                TitleSimilarity.between(subject.title(), original));

        return new MetadataCandidate(NAME,
                item.path("id").asString(null),
                displayName(item),
                yearOf(item.path("date").asString(null)),
                score,
                item.toString());
    }

    /** 有中译名用中译名，没有就用原名。 */
    private static String displayName(JsonNode item) {
        String chinese = item.path("name_cn").asString(null);
        return chinese == null || chinese.isBlank() ? item.path("name").asString(null) : chinese;
    }

    private static Integer yearOf(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ProviderUnavailableException(NAME + " 返回了无法解析的响应", e);
        }
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法编码搜索关键词", e);
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
```

- [ ] **Step 7: 写 TMDB 提供者**

`src/main/java/com/mymedia/metadata/TmdbProvider.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TMDB 提供者：只管视频域。
 *
 * <p><b>缺 API key 时 {@link #available()} 返回 false，链安静跳过它，不算错误。</b>
 * 这条路径是必须保住的——别人克隆这个仓库时没有 key，演示不能因此崩掉
 * （演示数据本来也靠本地 NFO，见 {@code LocalNfoProvider}）。
 *
 * <p><b>诚实声明</b>：本机没有 TMDB key，端点与字段名按官方文档写、由本地桩服务器
 * 覆盖，<b>没有真机验证</b>。讲解文档里要照实说，不要声称"对接并验证了 TMDB"。
 */
@Component
class TmdbProvider implements MetadataProvider {

    static final String NAME = "TMDB";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpProviderSupport http;
    private final MetadataProperties properties;
    private final RestClient client;

    TmdbProvider(HttpProviderSupport http, MetadataProperties properties) {
        this.http = http;
        this.properties = properties;
        this.client = http.client(properties.tmdb().baseUrl());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        // 影视归 TMDB，漫画与图集归 Bangumi
        return domain == LibraryDomain.VIDEO;
    }

    @Override
    public boolean available() {
        return !properties.tmdb().apiKey().isBlank();
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.SEARCH_CACHE,
               key = "'tmdb:' + #subject.title() + ':' + #subject.year()")
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        Optional<String> response = subject.year() == null
                ? http.get(NAME, client, "/search/movie?api_key={key}&language={lang}&query={query}",
                        properties.tmdb().apiKey(), properties.tmdb().language(), subject.title())
                : http.get(NAME, client,
                        "/search/movie?api_key={key}&language={lang}&query={query}&year={year}",
                        properties.tmdb().apiKey(), properties.tmdb().language(),
                        subject.title(), subject.year());
        if (response.isEmpty()) {
            return List.of();
        }

        JsonNode root = parse(response.get());
        List<MetadataCandidate> candidates = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String title = item.path("title").asString(null);
            String originalTitle = item.path("original_title").asString(null);
            double score = Math.max(
                    TitleSimilarity.between(subject.title(), title),
                    TitleSimilarity.between(subject.title(), originalTitle));
            candidates.add(new MetadataCandidate(NAME,
                    item.path("id").asString(null), title,
                    yearOf(item.path("release_date").asString(null)),
                    score, item.toString()));
        }
        return candidates;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.DETAIL_CACHE,
               key = "'tmdb:' + #candidate.externalId()")
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<String> response = http.get(NAME, client, "/movie/{id}?api_key={key}&language={lang}",
                candidate.externalId(), properties.tmdb().apiKey(), properties.tmdb().language());
        if (response.isEmpty()) {
            return Optional.empty();
        }

        JsonNode item = parse(response.get());
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, MetadataFields.TITLE, item.path("title").asString(null));
        putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, item.path("original_title").asString(null));
        putIfPresent(fields, MetadataFields.SUMMARY, item.path("overview").asString(null));
        putIfPresent(fields, MetadataFields.RELEASE_DATE, item.path("release_date").asString(null));
        putIfPresent(fields, MetadataFields.RATING, item.path("vote_average").asString(null));

        Map<String, String> extras = new LinkedHashMap<>();
        JsonNode companies = item.path("production_companies");
        if (companies.isArray() && !companies.isEmpty()) {
            putIfPresent(extras, "studio", companies.path(0).path("name").asString(null));
        }

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, candidate.externalId(),
                fields, extras, response.get()));
    }

    private static Integer yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(releaseDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ProviderUnavailableException(NAME + " 返回了无法解析的响应", e);
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='BangumiProviderTest,TmdbProviderTest,MetadataResolverTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`BangumiProviderTest` 12 个、`TmdbProviderTest` 7 个用例通过。

> 单元测试里直接 `new BangumiProvider(...)`，没有 Spring 代理，因此 `@Cacheable` 不生效——这正是我们想要的：测试断言的是协议本身，缓存行为不该混进来。

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/metadata src/test/java/com/mymedia/metadata
git commit -m "feat: 添加 Bangumi 与 TMDB 提供者

状态码到语义的映射是 HttpProviderSupport 的主要职责：
404 是'没找到'（正常），401/429/5xx/连不上才是'不可用'（重试）。
混为一谈会让冷门条目在任务表里永远重试。

Bangumi 的协议事实全部实测：无需鉴权、搜索是 POST、
搜索结果没有 summary——这正是 SPI 把 search 与 fetch 分两步的现实依据。

TMDB 缺 key 时 available() 返回 false，链安静跳过；
其端点形状按文档写、由本地桩覆盖，未经真机验证，讲解文档要照实说。

桩服务器用 JDK 自带的 com.sun.net.httpserver，不引 MockWebServer，
也不赌 MockRestServiceServer 对 RestClient 的支持。"
```

---

## Task 11: `METADATA_FETCH` 任务、候选队列与合集填充

**Files:**
- Create: `src/main/resources/db/migration/V11__scrape_candidate.sql`
- Create: `src/main/java/com/mymedia/metadata/ScrapeCandidateRecord.java`
- Create: `src/main/java/com/mymedia/metadata/ScrapeCandidateStore.java`
- Create: `src/main/java/com/mymedia/metadata/ScrapeCandidateService.java`
- Create: `src/main/java/com/mymedia/metadata/SubjectFactory.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataTrigger.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataFetchJobHandler.java`
- Create: `src/main/java/com/mymedia/metadata/MetadataEventListener.java`
- Create: `src/main/java/com/mymedia/metadata/web/ScrapeCandidateController.java`
- Modify: `src/main/java/com/mymedia/metadata/NfoParser.java`（读 Kodi 的 `<set>`）
- Modify: `src/main/java/com/mymedia/metadata/TmdbProvider.java`（读 `belongs_to_collection`）
- Modify: `src/main/java/com/mymedia/metadata/package-info.java`（扩大允许依赖）
- Modify: `src/main/java/com/mymedia/video/VideoCatalogService.java`（合集填充与待刮削查询）
- Modify: `src/main/java/com/mymedia/image/ImageCatalogService.java`（待刮削查询）
- Test: `src/test/java/com/mymedia/metadata/MetadataFetchJobTest.java`

**Interfaces:**
- Consumes: `MetadataResolver`、`ResolutionResult`、`MetadataProvider`（Task 9、10）、`JobHandler`、`JobQueue`、`Job`（计划 01）、`VideoItemCreated`、`ImageNodeCreated`、`LibraryScanCompleted`（计划 02–04）
- Produces:
  - `public record ScrapeCandidateRecord(Long id, LibraryDomain domain, Long targetId, String provider, String externalId, String title, Integer year, double score, String payload, Instant createdAt)`
  - `public class ScrapeCandidateService` — `candidatesFor(LibraryDomain, Long)`、`confirm(Long candidateId)`、`ignore(LibraryDomain, Long targetId)`
  - `public class MetadataTrigger` — `public Long request(LibraryDomain domain, Long targetId)`
  - `class MetadataFetchJobHandler implements JobHandler`（package-private）— `static final String JOB_TYPE = "METADATA_FETCH"`
  - `VideoCatalogService` 新增：`attachToCollection(Long itemId, String collectionName)`、`itemsPendingScrape(Long libraryId, int limit)`
  - `ImageCatalogService` 新增：`nodesPendingScrape(Long libraryId, int limit)`
  - HTTP：`GET /api/scrape/candidates?domain=&targetId=`、`POST /api/scrape/candidates/{id}/confirm`、`POST /api/scrape/ignore?domain=&targetId=`

### 候选表用两个可空外键，不用多态列

与 `share_link` 一致（spec §6.6）：`video_item_id` 与 `image_node_id` 各一个可空外键，`CHECK (num_nonnulls(...) = 1)` 保证恰有一个非空。多态列（`target_type` + `target_id`）在 PostgreSQL 里建不了引用完整性约束，删掉条目会留下悬空候选；两个可空外键让级联删除替我们清场。

### 合集填充：`extras` 里的 `collection` 键

计划 03 建了 `collection` / `collection_item` 两张表但从没往里写过东西。数据从哪来？两个现成的来源：

- **Kodi 的 `<set><name>指环王三部曲</name></set>`** —— 这是 .nfo 的标准写法，**演示数据不需要任何 API key 就能演示合集**。
- **TMDB 详情里的 `belongs_to_collection.name`**。

两者都写进 `MetadataPatch.extras` 的 `collection` 键，由任务处理器统一 find-or-create。

- [ ] **Step 1: 写会失败的端到端测试**

`src/test/java/com/mymedia/metadata/MetadataFetchJobTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.MetadataFields;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "mymedia.preview.root=target/test-derived")
class MetadataFetchJobTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    VideoCatalogService videoCatalog;

    @Autowired
    ScrapeCandidateService candidateService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    private MediaLibrary scanWith(List<String> providers, String... relativePaths) throws IOException {
        for (String relative : relativePaths) {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            if (relative.endsWith(".nfo")) {
                Files.writeString(file, """
                        <movie>
                          <title>大雄兔</title>
                          <plot>一只巨兔与三个坏蛋的故事。</plot>
                          <premiered>2008-05-20</premiered>
                          <set><name>Blender 开源电影</name></set>
                        </movie>
                        """, StandardCharsets.UTF_8);
            } else {
                Files.write(file, new byte[1024]);
            }
        }
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        libraryService.setMetadataProviders(library.getId(), providers);
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();   // 扫描；事件监听器排出预览与刮削任务
        jobPoller.pollOnce();   // 执行它们
        return library;
    }

    private VideoItem onlyItem() {
        List<VideoItem> items = videoCatalog.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private String registerAdmin() {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.ADMIN);
        accessService.grant(user.getId(), library.getId());
        return username;
    }

    @Test
    void scrapesFromTheLocalNfoWithoutAnyNetworkAccess() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");

        VideoItem item = onlyItem();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, release_date, scrape_status, scrape_source"
                        + " FROM video_item WHERE id = ?", item.getId());

        assertThat(row.get("title")).isEqualTo("大雄兔");
        assertThat(row.get("summary")).isEqualTo("一只巨兔与三个坏蛋的故事。");
        assertThat(row.get("release_date")).hasToString("2008-05-20");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(row.get("scrape_source")).isEqualTo("LocalNfo");
    }

    @Test
    void fillsTheCollectionFromTheKodiSetTag() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");

        VideoItem item = onlyItem();
        String collectionName = jdbc.queryForObject("""
                SELECT c.name FROM collection c
                  JOIN collection_item ci ON ci.collection_id = c.id
                 WHERE ci.video_item_id = ?
                """, String.class, item.getId());

        assertThat(collectionName).isEqualTo("Blender 开源电影");
    }

    @Test
    void collectionIsFoundOrCreatedRatherThanDuplicated() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        VideoItem item = onlyItem();

        // 再刮一次
        jdbc.update("UPDATE video_item SET scrape_status = 'PENDING' WHERE id = ?", item.getId());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM collection WHERE library_id = ?",
                Integer.class, library.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM collection_item WHERE video_item_id = ?",
                Integer.class, item.getId())).isEqualTo(1);
    }

    @Test
    void withoutAnyLocalFileItFallsBackToTheFilenameQuietly() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/某个自制视频.mp4");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT scrape_status, scrape_source FROM video_item WHERE id = ?",
                onlyItem().getId());

        // 找不到是正常状态，不是错误
        assertThat(row.get("scrape_status")).isEqualTo("NO_MATCH");
        assertThat(row.get("scrape_source")).isEqualTo("Filename");
    }

    @Test
    void libraryWithoutProvidersIsMarkedNotApplicableAndNoJobIsEnqueued() throws IOException {
        scanWith(List.of(), "电影/某个自制视频.mp4");

        assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                String.class, onlyItem().getId())).isEqualTo("NOT_APPLICABLE");
        // 界面零刮削噪音，连任务都不该排
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'METADATA_FETCH'", Integer.class)).isZero();
    }

    @Test
    void rescanDoesNotReScrapeAnAlreadyMatchedItem() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        int firstRound = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'METADATA_FETCH'", Integer.class);

        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();

        // 补齐只挑 PENDING 的条目，已经 MATCHED 的不再打扰外部服务
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'METADATA_FETCH'", Integer.class))
                .isEqualTo(firstRound);
    }

    @Test
    void candidateEndpointsListConfirmAndIgnore() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        String admin = registerAdmin();

        // 手工塞两个中等置信度候选，模拟外部刮削器给出的待确认结果
        jdbc.update("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, year, score, payload)
                VALUES (?, 'LocalNfo', 'c1', '候选一', 2008, 0.62, '{}'::jsonb),
                       (?, 'LocalNfo', 'c2', '候选二', 2009, 0.55, '{}'::jsonb)
                """, item.getId(), item.getId());
        videoCatalog.updateScrapeStatus(item.getId(), com.mymedia.shared.ScrapeStatus.NEEDS_REVIEW);

        mockMvc.perform(get("/api/scrape/candidates")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                // 高分在前，用户第一眼看到最可能的那个
                .andExpect(jsonPath("$[0].title").value("候选一"));

        mockMvc.perform(post("/api/scrape/ignore")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isNoContent());

        assertThat(candidateService.candidatesFor(LibraryDomain.VIDEO, item.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                String.class, item.getId())).isEqualTo("NO_MATCH");
    }

    @Test
    void confirmingACandidateAppliesItAndClearsTheQueue() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        VideoItem item = onlyItem();
        String admin = registerAdmin();
        jdbc.update("UPDATE video_item SET title = '待确认', scrape_status = 'NEEDS_REVIEW' WHERE id = ?",
                item.getId());

        Long candidateId = jdbc.queryForObject("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, year, score, payload)
                VALUES (?, 'LocalNfo', '大雄兔.nfo', '大雄兔', 2008, 0.62, '{}'::jsonb)
                RETURNING id
                """, Long.class, item.getId());

        mockMvc.perform(post("/api/scrape/candidates/{id}/confirm", candidateId)
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.title").value("大雄兔"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, scrape_status FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("大雄兔");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(candidateService.candidatesFor(LibraryDomain.VIDEO, item.getId())).isEmpty();
    }

    @Test
    void candidatesAreRemovedWhenTheItemIsDeleted() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        jdbc.update("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, score, payload)
                VALUES (?, 'LocalNfo', 'c1', '候选', 0.5, '{}'::jsonb)
                """, item.getId());

        jdbc.update("DELETE FROM video_item WHERE id = ?", item.getId());

        // 两个可空外键 + ON DELETE CASCADE：不会留下悬空候选
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scrape_candidate", Integer.class)).isZero();
    }

    @Test
    void candidateRowMustPointAtExactlyOneTarget() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO scrape_candidate (provider, external_id, title, score, payload)
                VALUES ('LocalNfo', 'c1', '无主候选', 0.5, '{}'::jsonb)
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void unauthorisedUserCannotSeeCandidates() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/scrape/candidates")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=MetadataFetchJobTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`ScrapeCandidateService` 不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V11__scrape_candidate.sql`：

```sql
-- ============================================================
-- 刮削候选：scrape_status = NEEDS_REVIEW 时的待确认列表。
--
-- 与 share_link 一致，用两个可空外键 + CHECK 恰有一个非空，
-- 而不是 (target_type, target_id) 多态列：多态外键在 PostgreSQL 里
-- 建不了引用完整性约束，删掉条目会留下悬空候选。详见 spec 6.6。
-- ============================================================

CREATE TABLE scrape_candidate (
    id            BIGSERIAL PRIMARY KEY,
    video_item_id BIGINT      REFERENCES video_item (id) ON DELETE CASCADE,
    image_node_id BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,
    provider      VARCHAR(32) NOT NULL,
    external_id   VARCHAR(64),
    title         TEXT,
    year          INT,
    -- 0.000–1.000，来自 TitleSimilarity 的二元组 Dice 系数
    score         NUMERIC(4,3) NOT NULL,
    -- 搜索结果原样存下来，用户确认时不必再查一次
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scrape_candidate_target CHECK (
        num_nonnulls(video_item_id, image_node_id) = 1)
);

CREATE INDEX idx_scrape_candidate_video ON scrape_candidate (video_item_id, score DESC);
CREATE INDEX idx_scrape_candidate_image ON scrape_candidate (image_node_id, score DESC);

-- ------------------------------------------------------------
-- 合集按 (库, 名字) find-or-create，需要这个唯一键才能用 ON CONFLICT。
-- 计划 03 的 V6 建 collection 表时还没有写入方，所以没建它。
-- ------------------------------------------------------------
ALTER TABLE collection ADD CONSTRAINT uq_collection_library_name UNIQUE (library_id, name);
```

- [ ] **Step 4: 写候选表的读写与服务**

`src/main/java/com/mymedia/metadata/ScrapeCandidateRecord.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;

import java.time.Instant;

/** {@code scrape_candidate} 的一行。 */
public record ScrapeCandidateRecord(
        Long id,
        LibraryDomain domain,
        Long targetId,
        String provider,
        String externalId,
        String title,
        Integer year,
        double score,
        String payload,
        Instant createdAt) {
}
```

`src/main/java/com/mymedia/metadata/ScrapeCandidateStore.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code scrape_candidate} 的读写。走 {@link JdbcTemplate}：{@code payload} 是 jsonb，
 * 按项目约定不做 JPA 映射。
 */
@Component
class ScrapeCandidateStore {

    private static final RowMapper<ScrapeCandidateRecord> MAPPER = (rs, rowNum) -> {
        Long videoItemId = (Long) rs.getObject("video_item_id");
        return new ScrapeCandidateRecord(
                rs.getLong("id"),
                videoItemId != null ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                videoItemId != null ? videoItemId : rs.getLong("image_node_id"),
                rs.getString("provider"),
                rs.getString("external_id"),
                rs.getString("title"),
                (Integer) rs.getObject("year"),
                rs.getDouble("score"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcTemplate jdbc;

    ScrapeCandidateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 覆盖式写入：一次刮削的候选就是这个条目当前的全部候选。 */
    void replaceAll(LibraryDomain domain, Long targetId, List<MetadataCandidate> candidates) {
        deleteAll(domain, targetId);
        String column = columnOf(domain);
        for (MetadataCandidate candidate : candidates) {
            jdbc.update("INSERT INTO scrape_candidate (" + column + ", provider, external_id,"
                            + " title, year, score, payload) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                    targetId, candidate.provider(), candidate.externalId(),
                    candidate.title(), candidate.year(), candidate.score(),
                    candidate.payload() == null ? "{}" : candidate.payload());
        }
    }

    void deleteAll(LibraryDomain domain, Long targetId) {
        jdbc.update("DELETE FROM scrape_candidate WHERE " + columnOf(domain) + " = ?", targetId);
    }

    List<ScrapeCandidateRecord> findByTarget(LibraryDomain domain, Long targetId) {
        return jdbc.query("SELECT * FROM scrape_candidate WHERE " + columnOf(domain) + " = ?"
                + " ORDER BY score DESC, id", MAPPER, targetId);
    }

    ScrapeCandidateRecord getById(Long id) {
        return jdbc.query("SELECT * FROM scrape_candidate WHERE id = ?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(() -> new com.mymedia.shared.NotFoundException("找不到刮削候选 id=" + id));
    }

    /** 列名由枚举决定，不是外部输入，拼进 SQL 是安全的。 */
    private static String columnOf(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_id" : "image_node_id";
    }
}
```

`src/main/java/com/mymedia/metadata/ScrapeCandidateService.java`：

```java
package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 待确认队列：列出候选、一键确认、一键忽略。
 *
 * <p>确认时才去取详情——搜索结果本来就不含简介（Bangumi 实测如此），
 * 而中等置信度的候选大多数会被丢弃，提前取详情等于白发一次请求。
 */
@Service
public class ScrapeCandidateService {

    private final ScrapeCandidateStore store;
    private final SubjectFactory subjectFactory;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final Map<String, MetadataProvider> providersByName = new LinkedHashMap<>();

    ScrapeCandidateService(ScrapeCandidateStore store,
                           SubjectFactory subjectFactory,
                           VideoCatalogService videoCatalog,
                           ImageCatalogService imageCatalog,
                           List<MetadataProvider> providers) {
        this.store = store;
        this.subjectFactory = subjectFactory;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        providers.forEach(provider -> providersByName.put(provider.name(), provider));
    }

    @Transactional(readOnly = true)
    public List<ScrapeCandidateRecord> candidatesFor(LibraryDomain domain, Long targetId) {
        return store.findByTarget(domain, targetId);
    }

    /** 端点要先按 id 拿到候选，才知道该校验哪个媒体库的访问权。 */
    @Transactional(readOnly = true)
    public ScrapeCandidateRecord candidateById(Long candidateId) {
        return store.getById(candidateId);
    }

    /** 用户选中了某个候选：取详情、应用、清空队列。 */
    @Transactional
    public MetadataSnapshot confirm(Long candidateId) {
        ScrapeCandidateRecord candidate = store.getById(candidateId);
        MetadataProvider provider = providersByName.get(candidate.provider());
        if (provider == null) {
            throw new NotFoundException("候选来自一个已经不存在的提供者: " + candidate.provider());
        }

        ScrapeSubject subject = subjectFactory.create(candidate.domain(), candidate.targetId());
        Optional<MetadataPatch> patch = provider.fetch(subject, new MetadataCandidate(
                candidate.provider(), candidate.externalId(), candidate.title(),
                candidate.year(), candidate.score(), candidate.payload()));
        if (patch.isEmpty()) {
            throw new NotFoundException("该候选在提供者侧已不存在，请重新刮削");
        }

        applyMetadata(candidate.domain(), candidate.targetId(), patch.get(), ScrapeStatus.MATCHED);
        store.deleteAll(candidate.domain(), candidate.targetId());
        return snapshot(candidate.domain(), candidate.targetId());
    }

    /** 用户认为都不对：清空队列并置 {@code NO_MATCH}，界面从此安静。 */
    @Transactional
    public void ignore(LibraryDomain domain, Long targetId) {
        store.deleteAll(domain, targetId);
        updateStatus(domain, targetId, ScrapeStatus.NO_MATCH);
    }

    private void applyMetadata(LibraryDomain domain, Long targetId,
                               MetadataPatch patch, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.applyMetadata(targetId, patch, status);
            case IMAGE -> imageCatalog.applyMetadata(targetId, patch, status);
        }
    }

    private void updateStatus(LibraryDomain domain, Long targetId, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.updateScrapeStatus(targetId, status);
            case IMAGE -> imageCatalog.updateScrapeStatus(targetId, status);
        }
    }

    private MetadataSnapshot snapshot(LibraryDomain domain, Long targetId) {
        return domain == LibraryDomain.VIDEO
                ? videoCatalog.metadataOf(targetId)
                : imageCatalog.metadataOf(targetId);
    }
}
```

- [ ] **Step 5: 写主体构造器**

`src/main/java/com/mymedia/metadata/SubjectFactory.java`：

```java
package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把两个域的条目抹平成 {@link ScrapeSubject}。
 *
 * <p>这个类是"刮削不做 SPI 倒置"的具体形态：域间差异就这么二三十行，
 * 集中在一处、看得见摸得着。倒置成 SPI 只是把这段 switch 拆成两个类分别放进
 * {@code video} 与 {@code image}，代码没变少，还多了一层间接。
 */
@Component
class SubjectFactory {

    /** 从路径里认年份：1900–2099，避免把 1080p 之类的数字当成年份。 */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;

    SubjectFactory(VideoCatalogService videoCatalog,
                   ImageCatalogService imageCatalog,
                   ScannedFileQueryService scannedFiles,
                   LibraryService libraryService) {
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
    }

    ScrapeSubject create(LibraryDomain domain, Long targetId) {
        return domain == LibraryDomain.VIDEO ? forVideo(targetId) : forImage(targetId);
    }

    private ScrapeSubject forVideo(Long itemId) {
        VideoItem item = videoCatalog.getItem(itemId);
        List<VideoFile> files = videoCatalog.filesOf(itemId);

        Path path = null;
        Integer year = null;
        if (!files.isEmpty()) {
            String relativePath = scannedFiles.getById(files.get(0).getScannedFileId())
                    .getRelativePath();
            path = rootOf(item.getLibraryId()).resolve(relativePath);
            year = yearIn(relativePath);
        }
        return new ScrapeSubject(LibraryDomain.VIDEO, itemId, item.getLibraryId(),
                item.getTitle(), year, path);
    }

    private ScrapeSubject forImage(Long nodeId) {
        ImageNode node = imageCatalog.getNode(nodeId);
        List<ImageFile> pages = imageCatalog.pagesOf(nodeId);

        Path path = null;
        if (!pages.isEmpty()) {
            ImageFile firstPage = pages.get(0);
            Path filePath = rootOf(node.getLibraryId())
                    .resolve(scannedFiles.getById(firstPage.getScannedFileId()).getRelativePath());
            // 压缩包节点：主体就是压缩包本身；散图目录：主体是那个目录
            path = firstPage.getArchiveEntryName() != null ? filePath : filePath.getParent();
        }
        String title = node.getTitle() == null || node.getTitle().isBlank()
                ? node.getName() : node.getTitle();
        return new ScrapeSubject(LibraryDomain.IMAGE, nodeId, node.getLibraryId(),
                title, null, path);
    }

    private Path rootOf(Long libraryId) {
        return Path.of(libraryService.getById(libraryId).getRootPath());
    }

    private static Integer yearIn(String relativePath) {
        Matcher matcher = YEAR.matcher(relativePath);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }
}
```

- [ ] **Step 6: 写任务、排队入口与事件监听**

`src/main/java/com/mymedia/metadata/MetadataTrigger.java`：

```java
package com.mymedia.metadata;

import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryDomain;
import org.springframework.stereotype.Service;

/** 刮削任务的排队入口。走 {@code dedup_key}，重复调用只会得到同一个待办任务。 */
@Service
public class MetadataTrigger {

    private final JobQueue jobQueue;

    MetadataTrigger(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    public Long request(LibraryDomain domain, Long targetId) {
        String payload = "{\"domain\":\"" + domain.name() + "\",\"targetId\":" + targetId + "}";
        String dedupKey = MetadataFetchJobHandler.JOB_TYPE + ":" + domain.name() + ":" + targetId;
        return jobQueue.enqueue(MetadataFetchJobHandler.JOB_TYPE, payload, dedupKey);
    }
}
```

`src/main/java/com/mymedia/metadata/MetadataFetchJobHandler.java`：

```java
package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@code METADATA_FETCH}：跑一遍提供者链并把结论写回条目。
 *
 * <p>四种结论各有各的落点：
 * <ul>
 *   <li>{@code MATCHED} → 写字段、清空候选队列、顺带填合集</li>
 *   <li>{@code NEEDS_REVIEW} → <b>只写候选，一个字段都不动</b></li>
 *   <li>{@code NO_MATCH} → 应用文件名兜底的结果，安静收场</li>
 *   <li>{@code ERROR} → 置 ERROR 并<b>抛异常</b>，交给任务表按指数退避重试</li>
 * </ul>
 */
@Component
class MetadataFetchJobHandler implements JobHandler {

    static final String JOB_TYPE = "METADATA_FETCH";

    /** {@code MetadataPatch.extras} 里表示所属合集的键。 */
    static final String COLLECTION_KEY = "collection";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(MetadataFetchJobHandler.class);

    private final SubjectFactory subjectFactory;
    private final MetadataResolver resolver;
    private final LibraryService libraryService;
    private final ScrapeCandidateStore candidateStore;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;

    MetadataFetchJobHandler(SubjectFactory subjectFactory,
                            MetadataResolver resolver,
                            LibraryService libraryService,
                            ScrapeCandidateStore candidateStore,
                            VideoCatalogService videoCatalog,
                            ImageCatalogService imageCatalog) {
        this.subjectFactory = subjectFactory;
        this.resolver = resolver;
        this.libraryService = libraryService;
        this.candidateStore = candidateStore;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = MAPPER.readTree(job.getPayload());
        LibraryDomain domain = LibraryDomain.valueOf(payload.path("domain").asString());
        Long targetId = payload.path("targetId").asLong();

        ScrapeSubject subject = subjectFactory.create(domain, targetId);
        List<String> providers = libraryService.metadataProvidersOf(subject.libraryId());
        if (providers.isEmpty()) {
            // 防守：本该由事件监听器拦下，走到这里说明配置刚被清空
            updateStatus(domain, targetId, ScrapeStatus.NOT_APPLICABLE);
            return;
        }

        ResolutionResult result = resolver.resolve(subject, providers);
        switch (result.status()) {
            case MATCHED -> {
                applyMetadata(domain, targetId, result.patch(), ScrapeStatus.MATCHED);
                candidateStore.deleteAll(domain, targetId);
                attachCollection(domain, targetId, result.patch());
            }
            case NEEDS_REVIEW -> {
                // 绝不在低置信度下强行写入：只存候选，等用户拍板
                candidateStore.replaceAll(domain, targetId, result.candidates());
                updateStatus(domain, targetId, ScrapeStatus.NEEDS_REVIEW);
            }
            case NO_MATCH -> {
                if (result.patch() != null) {
                    applyMetadata(domain, targetId, result.patch(), ScrapeStatus.NO_MATCH);
                } else {
                    updateStatus(domain, targetId, ScrapeStatus.NO_MATCH);
                }
                candidateStore.deleteAll(domain, targetId);
            }
            case ERROR -> {
                updateStatus(domain, targetId, ScrapeStatus.ERROR);
                throw new ProviderUnavailableException(
                        "刮削失败，等待重试：" + domain + " id=" + targetId);
            }
            default -> log.warn("未预期的刮削结论 {}", result.status());
        }
    }

    /**
     * 合集填充。
     *
     * <p>计划 03 建了 {@code collection} 两张表却一直没数据；来源在这里补上：
     * Kodi 的 {@code <set><name>} 与 TMDB 的 {@code belongs_to_collection.name}
     * 都写进 {@code extras} 的 {@code collection} 键。
     *
     * <p>只有视频域有合集——图片域的树本身就表达了层级聚合，再叠一层是冗余（spec 6.4）。
     */
    private void attachCollection(LibraryDomain domain, Long targetId, MetadataPatch patch) {
        if (domain != LibraryDomain.VIDEO || patch == null) {
            return;
        }
        String collection = patch.extras().get(COLLECTION_KEY);
        if (collection != null && !collection.isBlank()) {
            videoCatalog.attachToCollection(targetId, collection.trim());
        }
    }

    private void applyMetadata(LibraryDomain domain, Long targetId,
                               MetadataPatch patch, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.applyMetadata(targetId, patch, status);
            case IMAGE -> imageCatalog.applyMetadata(targetId, patch, status);
        }
    }

    private void updateStatus(LibraryDomain domain, Long targetId, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.updateScrapeStatus(targetId, status);
            case IMAGE -> imageCatalog.updateScrapeStatus(targetId, status);
        }
    }
}
```

`src/main/java/com/mymedia/metadata/MetadataEventListener.java`：

```java
package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 新条目排刮削任务；扫描结束时把还是 {@code PENDING} 的条目补排一遍。
 *
 * <p><b>没有配置刮削器的库根本不排任务</b>，条目直接置 {@code NOT_APPLICABLE}——
 * spec 7.2 规则 3 要的"界面零刮削噪音"就是在这里兑现的，而不是靠界面去过滤。
 *
 * <p>补齐只挑 {@code PENDING}：已经 {@code MATCHED} 或用户已经忽略过（{@code NO_MATCH}）
 * 的条目不再打扰外部服务。要重刮就把状态改回 PENDING，语义清楚。
 */
@Component
class MetadataEventListener {

    private static final Logger log = LoggerFactory.getLogger(MetadataEventListener.class);

    private static final int BATCH_LIMIT = 500;

    private final LibraryService libraryService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final MetadataTrigger trigger;

    MetadataEventListener(LibraryService libraryService,
                          VideoCatalogService videoCatalog,
                          ImageCatalogService imageCatalog,
                          MetadataTrigger trigger) {
        this.libraryService = libraryService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.trigger = trigger;
    }

    @TransactionalEventListener
    void onVideoItemCreated(VideoItemCreated event) {
        if (scrapingDisabled(event.libraryId())) {
            videoCatalog.updateScrapeStatus(event.itemId(), ScrapeStatus.NOT_APPLICABLE);
            return;
        }
        trigger.request(LibraryDomain.VIDEO, event.itemId());
    }

    @TransactionalEventListener
    void onImageNodeCreated(ImageNodeCreated event) {
        if (scrapingDisabled(event.libraryId())) {
            imageCatalog.updateScrapeStatus(event.nodeId(), ScrapeStatus.NOT_APPLICABLE);
            return;
        }
        trigger.request(LibraryDomain.IMAGE, event.nodeId());
    }

    @TransactionalEventListener
    void onScanCompleted(LibraryScanCompleted event) {
        if (scrapingDisabled(event.libraryId())) {
            return;
        }
        LibraryDomain domain = libraryService.getById(event.libraryId()).getDomain();
        List<Long> pending = switch (domain) {
            case VIDEO -> videoCatalog.itemsPendingScrape(event.libraryId(), BATCH_LIMIT);
            case IMAGE -> imageCatalog.nodesPendingScrape(event.libraryId(), BATCH_LIMIT);
        };
        pending.forEach(targetId -> trigger.request(domain, targetId));
        if (!pending.isEmpty()) {
            log.info("扫描完成后补排刮削 libraryId={} 数量={}", event.libraryId(), pending.size());
        }
    }

    private boolean scrapingDisabled(Long libraryId) {
        return libraryService.metadataProvidersOf(libraryId).isEmpty();
    }
}
```

- [ ] **Step 7: 写候选队列端点**

`src/main/java/com/mymedia/metadata/web/ScrapeCandidateController.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.ScrapeCandidateRecord;
import com.mymedia.metadata.ScrapeCandidateService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 待确认队列：列出、确认、忽略。 */
@RestController
@RequestMapping("/api/scrape")
class ScrapeCandidateController {

    private final ScrapeCandidateService candidateService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ScrapeCandidateController(ScrapeCandidateService candidateService,
                              VideoCatalogService videoCatalog,
                              ImageCatalogService imageCatalog,
                              LibraryAccessService accessService,
                              UserQueryService userQueryService) {
        this.candidateService = candidateService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/candidates")
    List<MetadataDto.CandidateResponse> list(@AuthenticationPrincipal UserDetails principal,
                                             @RequestParam LibraryDomain domain,
                                             @RequestParam Long targetId) {
        requireAccess(principal, domain, targetId);
        return candidateService.candidatesFor(domain, targetId).stream()
                .map(MetadataDto.CandidateResponse::from)
                .toList();
    }

    @PostMapping("/candidates/{id}/confirm")
    MetadataDto.Response confirm(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id) {
        ScrapeCandidateRecord candidate = candidateService.candidateById(id);
        requireAccess(principal, candidate.domain(), candidate.targetId());
        return MetadataDto.Response.from(candidateService.confirm(id));
    }

    @PostMapping("/ignore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void ignore(@AuthenticationPrincipal UserDetails principal,
                @RequestParam LibraryDomain domain,
                @RequestParam Long targetId) {
        requireAccess(principal, domain, targetId);
        candidateService.ignore(domain, targetId);
    }

    private void requireAccess(UserDetails principal, LibraryDomain domain, Long targetId) {
        Long libraryId = domain == LibraryDomain.VIDEO
                ? videoCatalog.getItem(targetId).getLibraryId()
                : imageCatalog.getNode(targetId).getLibraryId();
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        if (!accessService.canAccess(userId, libraryId)) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到该条目 id=" + targetId);
        }
    }
}
```

在 `src/main/java/com/mymedia/metadata/web/MetadataDto.java` 中追加：

```java
    record CandidateResponse(
            Long id,
            String provider,
            String externalId,
            String title,
            Integer year,
            double score) {

        static CandidateResponse from(com.mymedia.metadata.ScrapeCandidateRecord record) {
            return new CandidateResponse(record.id(), record.provider(), record.externalId(),
                    record.title(), record.year(), record.score());
        }
    }
```

- [ ] **Step 8: 补上合集来源与领域查询**

`src/main/java/com/mymedia/metadata/NfoParser.java` —— 在 `parseXml` 里，`genres` 那段之后追加：

```java
        // Kodi 的电影集合写法：<set><name>指环王三部曲</name></set>。
        // 演示数据靠它就能展示合集，不需要任何 API key。
        NodeList sets = root.getElementsByTagName("set");
        if (sets.getLength() > 0 && sets.item(0) instanceof Element set) {
            String setName = firstText(set, "name");
            // 旧版 Kodi 写成 <set>名字</set>，没有嵌套的 <name>
            putIfPresent(extras, "collection",
                    setName != null ? setName : set.getTextContent());
        }
```

`src/main/java/com/mymedia/metadata/TmdbProvider.java` —— 在 `fetch` 的 `extras` 部分追加：

```java
        putIfPresent(extras, "collection",
                item.path("belongs_to_collection").path("name").asString(null));
```

`src/main/java/com/mymedia/video/VideoCatalogService.java` 追加：

```java
    /**
     * 把条目挂进一个合集，合集按 (库, 名字) find-or-create。
     *
     * <p>多对多是有意的：一部电影可以同时属于「指环王三部曲」与「托尔金改编作品」。
     * 两条语句都带 {@code ON CONFLICT DO NOTHING}，重复刮削不会产生重复行。
     */
    @Transactional
    public void attachToCollection(Long itemId, String collectionName) {
        Long libraryId = getItem(itemId).getLibraryId();
        Long collectionId = jdbc.queryForObject("""
                WITH inserted AS (
                    INSERT INTO collection (library_id, name, sort_key)
                    VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                )
                SELECT id FROM inserted
                UNION ALL
                SELECT id FROM collection WHERE library_id = ? AND name = ?
                LIMIT 1
                """, Long.class,
                libraryId, collectionName, NaturalSortKey.of(collectionName),
                libraryId, collectionName);

        jdbc.update("INSERT INTO collection_item (collection_id, video_item_id)"
                + " VALUES (?, ?) ON CONFLICT DO NOTHING", collectionId, itemId);
    }

    /** 扫描完成后的刮削补齐用。只挑 PENDING——已匹配或已被用户忽略的不再打扰外部服务。 */
    @Transactional(readOnly = true)
    public List<Long> itemsPendingScrape(Long libraryId, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM video_item WHERE library_id = ? AND scrape_status = 'PENDING'"
                        + " ORDER BY id LIMIT ?", Long.class, libraryId, limit);
    }
```

> 这条 SQL 依赖 Step 3 在 V11 末尾补的 `uq_collection_library_name`。数据修改型 CTE
> 与后半段 SELECT 用的是同一个快照：首次插入时 `inserted` 返回新 id、后半段查不到；
> 冲突时 `inserted` 为空、后半段查到既有行。两种情况都只有一个分支产出行，
> 所以 `UNION ALL … LIMIT 1` 的取值是确定的。

`src/main/java/com/mymedia/image/ImageCatalogService.java` 追加：

```java
    /** 扫描完成后的刮削补齐用。 */
    @Transactional(readOnly = true)
    public List<Long> nodesPendingScrape(Long libraryId, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM image_node
                 WHERE library_id = ? AND scrape_status = 'PENDING' AND status = 'ACTIVE'
                 ORDER BY id LIMIT ?
                """, Long.class, libraryId, limit);
    }
```

- [ ] **Step 9: 扩大 `metadata` 的允许依赖**

修改 `src/main/java/com/mymedia/metadata/package-info.java` 的 `allowedDependencies`：

```java
        allowedDependencies = {"shared", "user", "library", "jobs", "scan", "scan::events",
                               "video", "video::events", "image", "image::events"})
```

- [ ] **Step 10: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='MetadataFetchJobTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`MetadataFetchJobTest` 11 个用例通过。

- [ ] **Step 11: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V11__scrape_candidate.sql src/main/java/com/mymedia src/test/java/com/mymedia/metadata
git commit -m "feat: 添加 METADATA_FETCH 任务、候选队列与合集填充

候选表与 share_link 一样用两个可空外键 + CHECK 恰有一个非空：
多态列在 PostgreSQL 里建不了引用完整性约束，删条目会留下悬空候选。

NEEDS_REVIEW 时只写候选、一个字段都不动；确认时才去取详情——
搜索结果本来就不含简介，而中等置信度的候选大多会被丢弃。

合集数据的来源补上了：Kodi 的 <set> 与 TMDB 的 belongs_to_collection，
前者让演示数据不需要任何 API key 就能展示合集。"
```

---

## Task 12: 全量验证、ADR 与讲解文档

**Files:**
- Test: `src/test/java/com/mymedia/preview/FfprobeSmokeTest.java`
- Modify: `src/test/java/com/mymedia/FlywayMigrationTest.java`（增加 V10/V11 迁移存在性断言）
- Create: `docs/adr/ADR-004-刮削链不做-SPI-倒置.md`
- Create: `docs/adr/ADR-005-刮削是可选增强.md`
- Create: `docs/walkthrough/05-预览与元数据.md`
- Modify: `docs/superpowers/plans/2026-08-17-00-总览与交接.md`（把 05 标记为已完成）

**Interfaces:**
- Consumes: 前 11 个任务的全部产出
- Produces: 可交付的阶段成果

- [ ] **Step 1: 写真机冒烟测试**

只此一个测试碰真的 ffprobe，本机没装就跳过——**跳过不是失败**。

`src/test/java/com/mymedia/preview/FfprobeSmokeTest.java`：

```java
package com.mymedia.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 唯一一个真的调用 ffprobe 的测试。
 *
 * <p>ffmpeg / ffprobe 烘焙在应用镜像里，开发机上不一定装了，因此本测试用
 * {@code assumeTrue} 守门：装了就跑，没装就跳过。<b>跳过不是失败</b>——
 * 全部解析逻辑都有纯单元测试覆盖，这里验证的只是"我们拼的命令行真的能被
 * 真正的 ffprobe 接受"。
 */
class FfprobeSmokeTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void realFfprobeAcceptsOurCommandLine(@TempDir Path tempDir) throws Exception {
        try {
            CommandResult version = runner.run(
                    List.of("ffprobe", "-version"), Duration.ofSeconds(10));
            assertThat(version.succeeded())
                    .as("ffprobe -version 失败: %s", version.stderr())
                    .isTrue();
        } catch (IOException e) {
            if (isMissingExecutable(e)) {
                assumeTrue(false, "本机没有 ffprobe，跳过真机验证（镜像里有）");
            }
            throw e;
        }

        Path fixture = tempDir.resolve("fixture.wav");
        Files.write(fixture, wavFixture());
        CommandResult result = runner.run(
                MediaCommands.probe("ffprobe", fixture),
                Duration.ofSeconds(20));

        assertThat(result.succeeded())
                .as("ffprobe 探测合法 WAV 失败: %s", result.stderr())
                .isTrue();
        FfprobeOutput output = FfprobeParser.parse(result.stdout());
        assertThat(output.durationSeconds()).isEqualTo(1);
        assertThat(output.audioCodec()).isNotBlank();
    }

    private static boolean isMissingExecutable(IOException failure) {
        Throwable cause = failure.getCause();
        if (!(cause instanceof IOException)) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.contains("error=2");
    }

    /** 构造一秒的无声 PCM WAV，不依赖 ffmpeg 生成测试输入。 */
    private static byte[] wavFixture() {
        int sampleRate = 8_000;
        int dataSize = sampleRate;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(wav, "RIFF");
        wav.putInt(36 + dataSize);
        putAscii(wav, "WAVE");
        putAscii(wav, "fmt ");
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate);
        wav.putShort((short) 1);
        wav.putShort((short) 8);
        putAscii(wav, "data");
        wav.putInt(dataSize);
        return wav.array();
    }

    private static void putAscii(ByteBuffer buffer, String value) {
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }
}
```

- [ ] **Step 2: 跑全量验证**

```powershell
# 在当前计划的 worktree 根目录执行，例如 D:\MyMedia-5
mvn -B -ntp verify *> verify.log
$exitCode = $LASTEXITCODE
Write-Output "EXIT=$exitCode"
Select-String -Path "verify.log" -Pattern "Tests run:|BUILD" | Select-Object -Last 20
```

Expected: `EXIT=0`，`BUILD SUCCESS`，`Failures: 0, Errors: 0`。

**若 `ModularityTests` 失败**，先看失败信息里是不是 "does not exist" 之类的命名接口问题——回到 Task 6 Step 1 检查那四个 `package-info.java`。

- [ ] **Step 3: 确认迁移在干净数据库上从头跑通**

```powershell
# 不设置 failIfNoTests=false，避免测试类未被发现时假绿
mvn -B -ntp test "-Dtest=FlywayMigrationTest" "-DfailIfNoTests=true"
```

Expected: `EXIT=0`。V1→V11 在全新容器上依次执行成功。

- [ ] **Step 4: 写 ADR-004**

`docs/adr/ADR-004-刮削链不做-SPI-倒置.md`：

```markdown
# ADR-004：刮削链不做 SPI 倒置

## 状态

已接受（2026-08-17，实施计划 05）

## 背景

`scan` 模块用 SPI 倒置解耦了领域：它定义 `LibraryContentBuilder`，由 `video` 与
`image` 各自实现并注册，`scan` 完全不认识这两个模块。加第三个域（音频）不需要
改扫描代码一行。

`preview` 与 `metadata` 面临形式上相同的问题——它们也要同时服务两个域。
是否照搬同一套倒置？

## 决策

**不倒置。** `preview` 与 `metadata` 直接依赖 `video` 与 `image`，
订阅它们的领域事件、调用它们的公开写回 API；两个领域模块绝不反向引用，
方向由 `ModularityTests` 的 `allowedDependencies` 强制。

## 理由

倒置买到的是"新增一个域不改上游代码"。这个收益是否成立，取决于**上游是否真的与领域无关**：

- **物理扫描真的与领域无关**：遍历目录、算指纹、对账、改名检测，没有一处需要知道
  文件是电影还是漫画页。倒置之后 `scan` 里一个 `if (domain == …)` 都没有。收益是实的。
- **刮削本身就是领域特定的**：TMDB 只管影视，Bangumi 的番剧与漫画走不同的 type 参数，
  NFO 的字段名两个域也不一样。倒置只能把这些判断从 `metadata` 挪进两个领域模块，
  判断的**数量一个不少**，还多了一层间接和一份要维护的接口契约。

具体形态可以看 `SubjectFactory`：两个域的差异一共二三十行，集中在一个类里，
一眼能看完。倒置成 SPI 就是把这段 switch 拆成两个类分别塞进 `video` 与 `image`。

预览生成同理：抽帧靠 ffmpeg、漫画封面靠读首页，这两件事没有共同的抽象可言，
硬造一个 `CoverSource` 接口只会得到两个互不相干的实现。

## 后果

- `preview` / `metadata` 的依赖列表里有 `video` 与 `image`，模块图上多两条边。
- 加入第三个域（音频）时，要改 `SubjectFactory` 与两个生成器——**这是预期内的**，
  因为那时确实要为音频写新的刮削与封面逻辑，不存在"不改就能用"的可能。
- 依赖方向仍然是单向的：领域模块不知道预览与刮削的存在，删掉这两个模块，
  视频与图片域照样能扫描、能播放、能阅读。

## 备选方案

**定义 `MetadataSubjectProvider` / `CoverSource` 两个 SPI 由领域模块实现。**
被否决：接口只有两个实现且永远只会有这么多、方法签名要迁就两个域的差异而变得含糊、
`metadata` 仍然要按 domain 选提供者。付出了间接的代价，没换到开闭。

## 附注

同一个项目里对"要不要倒置"做出两次相反的判断，各有各的理由——这比"我全都倒置了"
更能说明判断力。面试里被问到依赖倒置时，这两个案例是一组现成的对照。
```

- [ ] **Step 5: 写 ADR-005**

`docs/adr/ADR-005-刮削是可选增强.md`：

```markdown
# ADR-005：刮削是可选增强，不是必经步骤

## 状态

已接受（2026-08-17，实施计划 05）

## 背景

主流媒体库（Jellyfin / Emby）默认把刮削当作入库流程的一环：匹配不上的条目
显示为"未识别"，需要用户手工处理。

本项目的实际内容里有大量在任何数据库中都不存在的东西：个人录像、自制视频、
同人图集、冷门汉化。如果照搬那套流程，用户打开界面看到的会是满屏的报错。

## 决策

刮削是**往上加**，不是前置条件。四条落地规则：

1. **扫描完成的瞬间条目就可用**：标题来自文件名解析，封面来自视频抽帧 / 漫画首页，
   可播放可阅读。刮削失败不影响任何一项。
2. **按库配置**：`libraries.metadata_providers` 为空数组的库，条目直接置
   `NOT_APPLICABLE`，**连任务都不排**，界面零刮削噪音。已经排出的 `PENDING` 任务会在
   下一次扫描补齐或任务执行时收敛，不把配置修改伪装成回溯式取消。
3. **`NO_MATCH` 不是错误**：安静回落到文件名元数据，界面不显示为异常状态。
   只有网络故障与限流才置 `ERROR` 并重试。
4. **低置信度不写入**：中等相似度的结果进 `scrape_candidate` 等用户确认，
   绝不猜。

## 理由

- 一个把"找不到"当作错误的系统，会在最常见的使用场景里持续制造焦虑。
- 把刮削从关键路径上摘下来之后，外部 API 的可用性、限流、key 缺失都不再是
  影响可用性的因素——`TmdbProvider` 缺 key 时直接报告不可用，链跳过它，
  别人克隆这个仓库也能跑。
- 采用本地 `.nfo` 的演示内容时，`docker compose up` 无需任何 API key。

## 后果

- 用户可能看到由文件名推断出的不完美标题——**这是刻意的**，比一个空条目或
  一个红色感叹号好。
- `scrape_status` 有六个取值，比"成功/失败"两态复杂。这个复杂度是真实存在的，
  藏起来只会转移到别处。
- 优先级 `用户编辑 > 本地文件 > 刮削 > 文件名` 由两套既有机制表达——链的顺序
  管"谁先谁后"，`locked_fields` 管"谁不能被覆盖"——**没有第三套 tier 比较**。
```

- [ ] **Step 6: 写讲解文档**

`docs/walkthrough/05-预览与元数据.md`，按既有讲解文档的结构（做了什么 / 为什么这么做 / 坑在哪 / 怎么自己验证），必须覆盖：

1. **`derived_asset` 与那条迟到的外键**：为什么 `cover_asset_id` 的外键到 V10 才加，
   以及 `ON DELETE SET NULL` 如何兑现"派生目录删光后可全量重建"。
   附上重建的实际操作：
   ```sql
   -- 清空派生资源；所有 cover_asset_id 由外键自动置空
   DELETE FROM derived_asset;
   ```
   ```bash
   rm -rf ./data/derived
   # 触发一次扫描，补齐监听器会把全库重排
   ```
2. **`CommandRunner` 为什么存在**：不是为了抽象，是为了让解析逻辑变成纯单元测试，
   在没装 ffmpeg 的机器上也能跑完整套。
3. **`-ss` 的位置**：为什么放 `-i` 前，为什么这件事值得一个单元测试
   （两种写法都能出图，集成测试看不出差别）。
4. **雪碧图为什么默认 100 帧 10×10**：一个决定省掉的全部复杂度；实现仍允许通过预览配置覆盖；
   以及图块尺寸为什么要从生成结果读而不是重算。
5. **优先级的两套机制**：链的顺序 + `locked_fields`，`field_sources` 只用于展示。
   配一个"用户改过标题后再刮一次"的实际例子。
6. **二元组 Dice 系数 vs `pg_trgm` 三元组**：同一思路的两种落点，
   为什么中文用二元组、为什么不用编辑距离。
7. **404 与 429 的区别**：为什么把它们混为一谈会让冷门条目永远重试。
8. **XXE**：`.nfo` 是不可信输入，`disallow-doctype-decl` 为什么是唯一正确的默认值。
9. **诚实声明**：TMDB 的端点形状按文档写、由本地桩覆盖，**没有真机验证**；
   Bangumi 的协议事实是实测的。简历上不要说"对接并验证了 TMDB"。
10. **依赖方向**：为什么 `scan` 倒置而 `metadata` 不倒置（指向 ADR-004）。

- [ ] **Step 7: 更新总览与交接文件**

修改 `docs/superpowers/plans/2026-08-17-00-总览与交接.md`：

1. 计划表里把 05 那一行的状态改成 `✅ 已写（12 任务）`，并补一句"已执行完毕"（如果确实执行完了）。
2. §1 末尾那句"**代码一行未写**，仓库目前只有 `docs/`"已经过时——计划 01 早已执行完毕。
   改成当前真实状态。
3. §2 的迁移编号一行补上：`V10–V11（05）`，并注明 **V12 起留给计划 06**。
4. §2 的任务类型一行确认 `PREVIEW_GENERATE` / `SPRITE_GENERATE` / `METADATA_FETCH` 已落地。
5. §3 的"已实测验证的事实"表追加两行：
   - **Boot 4.1 用 Jackson 3（`tools.jackson.databind`），异常不受检，`asText` → `asString`** ——
     验证方式：`unzip -l target/*.jar` + `javap`
   - **Spring Modulith 的嵌套包默认是模块内部实现**，跨模块引用需要 `@NamedInterface`
     并在 `allowedDependencies` 里写 `模块::接口名` —— 验证方式：本计划 Task 6
6. §5「已知遗留」追加计划 05 的遗留项（见本计划 Self-Review）。

- [ ] **Step 8: 最终提交**

```powershell
# 在当前计划的 worktree 根目录执行，不要切回主 worktree
Remove-Item -Force "verify.log", "t.log" -ErrorAction SilentlyContinue
git add docs/adr/ADR-004-刮削链不做-SPI-倒置.md docs/adr/ADR-005-刮削是可选增强.md docs/walkthrough/05-预览与元数据.md docs/superpowers/plans/2026-08-17-00-总览与交接.md docs/superpowers/plans/2026-08-17-05-preview-metadata.md src/test/java/com/mymedia/FlywayMigrationTest.java src/test/java/com/mymedia/preview/FfprobeSmokeTest.java
git commit -m "docs: 完成预览与元数据阶段的 ADR 与讲解文档"
```

---

## Self-Review

### 1. Spec 覆盖

| spec 章节 / 要求 | 落点 |
|---|---|
| §4.3 `VideoItemCreated` → `metadata` / `preview` | Task 6 `PreviewEventListener`、Task 11 `MetadataEventListener` |
| §4.3 `ImageNodeCreated` → `metadata` / `preview` | 同上 |
| §4.3 `LibraryScanCompleted` → `preview`（批量补齐） | Task 6 `PreviewBackfill` |
| §6.2 `derived_asset` 表结构与"删光可重建" | Task 1（表 + 外键 + `DerivedAssetCascadeTest`） |
| §6.2 job type `PREVIEW_GENERATE` / `SPRITE_GENERATE` / `METADATA_FETCH` | Task 3、5、11 |
| §6.6 `scrape_candidate`（两个可空外键 + CHECK） | Task 11 V11 |
| §7.2 规则 1 无刮削亦可用 | Task 3、4（封面）+ Task 9 `FilenameProvider` |
| §7.2 规则 2 字段级来源 + 用户锁定 | Task 7 `FieldMergePolicy` / `locked_fields` / `field_sources` |
| §7.2 规则 3 按库配置，空数组不刮削 | Task 7 `metadataProvidersOf` + Task 11 事件监听器 |
| §7.2 规则 4 置信度分档 | Task 9 `MetadataResolver` + Task 11 候选队列 |
| §7.2 规则 5 `NO_MATCH` 不是错误 | Task 9（安静回落）+ Task 10（404 vs 429） |
| §7.2 规则 6 本地元数据文件优先 | Task 8 `LocalNfoProvider` + Task 9 链首位 |
| §7.2 规则 7 可插拔链式尝试 | Task 8 SPI + Task 9 `MetadataResolver` |
| §7.2 Provider 表（LocalNfo / TMDB / Bangumi / Filename） | Task 8、9、10 |
| §7.2 外部调用限流与缓存 | Task 10 `HttpProviderSupport` + `ProviderCacheConfig` |
| §8 进度条悬停显示雪碧图预览帧 | Task 5（图 + VTT，前端在 P12 消费） |
| §11 P8 ffprobe 探测、封面抽帧、缩略图、雪碧图 | Task 2–5 |
| §11 P9 刮削链 + 待确认队列 | Task 7–11 |
| §12 ADR #8「为什么刮削是可选增强」 | Task 12 ADR-005 |
| §13 风险「TMDB 需要 key，他人无法复现」 | Task 10 `available()` + Task 8 本地 NFO |
| §10 交付物：讲解文档 | Task 12 |

**未覆盖且属于本计划范围之外的**：`video_group.cover_asset_id` 与 `collection.cover_asset_id`
在 V10 里加了外键但没有任何代码往里写。合集封面与季封面属于展示层的选择题
（用第一个条目的封面代偿即可），留给计划 07 前端阶段决定，不在 P8–P9 的验收范围内。

### 2. 占位符扫描

已逐条检查，没有 "TBD" / "TODO" / "类似 Task N" / "适当处理错误" 一类的写法。
两处**看起来像**占位符、实际是有意为之的地方，各自写明了理由：

- Task 3 Step 8 的 `PreviewJobHandler` 里 `IMAGE_NODE` 分支先抛 `UnsupportedOperationException`：
  那时还排不出该载荷的任务，抛异常比返回成功诚实。Task 4 Step 5 补全。
- Task 3 Step 6 的 `requestSprite` 先用字面量 `"SPRITE_GENERATE"`：`SpriteJobHandler`
  在 Task 5 才存在。Task 5 Step 6 换回常量引用。

### 3. 类型一致性

逐个核对了跨任务的签名：

- `DerivedAssetService.prepare/record/find/getById/pathOf` —— Task 1 定义，Task 3、4、5、6 使用，一致。
- `CommandRunner.run(List<String>, Duration)` —— Task 2 定义，Task 3、5 与 `StubCommandRunner` 一致。
- `PreviewTrigger.requestVideoPreview/requestImagePreview/requestSprite` —— Task 3 定义，Task 5、6 使用，一致。
- `VideoCatalogService`：`applyProbe`(Task 3)、`assignCoverIfAbsent`(Task 3)、`itemsWithoutCover`(Task 3)、
  `applyMetadata/applyUserEdit/updateScrapeStatus/metadataOf`(Task 7)、
  `attachToCollection/itemsPendingScrape`(Task 11) —— 参数名统一为 `itemId`。
- `ImageCatalogService`：`openPageForProcessing/assignCoverIfAbsent/nodesWithoutCover`(Task 4)、
  四个元数据方法(Task 7)、`nodesPendingScrape`(Task 11) —— 参数名统一为 `nodeId`，
  与视频域同名同签名（只有参数名不同），刻意保持对称。
- `MetadataPatch` / `MetadataFields` / `FieldMergePolicy` / `ScrapeStatus` / `MetadataSnapshot`
  —— Task 7 定义于 `shared`，Task 8–11 使用，一致。
- `MetadataProvider.name/supports/available/search/fetch` —— Task 8 定义，
  `LocalNfoProvider`(8)、`FilenameProvider`(9)、`BangumiProvider`(10)、`TmdbProvider`(10)
  与 `MetadataResolverTest` 的手写替身全部实现同一套签名。
- `MetadataProperties` 的构造参数顺序 `(userAgent, minRequestInterval, autoApplyThreshold,
  reviewThreshold, bangumi, tmdb)` —— Task 9 定义，Task 10 的两个测试按同一顺序构造。
- `ProviderCacheConfig.SEARCH_CACHE` / `DETAIL_CACHE` —— Task 10 内部自洽。

### 4. 一处需要执行者留意的上游偏差

计划 02–04 把公开类型放进了嵌套包（`scan.spi`、`scan.event`、`video.event`、`image.event`）
却**没有写 `@NamedInterface`**，而计划 01 执行时给每个模块加了显式 `allowedDependencies`。
两件事叠在一起会让 `ApplicationModules.verify()` 在**计划 02 阶段**就失败。

本计划 Task 6 Step 1 提供了补齐用的四个 `package-info.java`。**执行计划 02 时若已经
自行解决过这个问题，Task 6 Step 1 跳过即可**；若采用了别的解法（例如把事件挪到模块根包），
则需要相应调整 Task 6 Step 2 的 `allowedDependencies` 写法。

### 5. 已知遗留（写给计划 06 及之后）

- `video_group` 与 `collection` 的封面没有生成逻辑（见 §1 末尾）。
- 缓存没有淘汰策略。上界是媒体库条目数，可接受；若将来接入了更多提供者再评估。
- 图片域对**每一个** ACTIVE 节点都会排一次刮削，包括纯中间目录（画师名一类）。
  它们大概率 `NO_MATCH` 后安静回落，代价是每个节点一次外部请求（受限流保护）。
  若实测噪音过大，收敛条件应当加在 `nodesPendingScrape` 的 SQL 里，而不是加在界面上。
- 迁移编号 **V12 起留给计划 06**。
