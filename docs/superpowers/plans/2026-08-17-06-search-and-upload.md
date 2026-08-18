# MyMedia 实施计划 06：检索、用户态与分片上传

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把媒体库从"能看"做到"找得到、理得清、分得出去、传得上来"：中文可用的双路径搜索、跨域标签、每用户收藏、免登录分享链接，以及支持断点续传与秒传的分片上传。

**Architecture:** 搜索、标签、收藏**不新开模块**——各领域模块提供自己的搜索与收藏，标签归 `metadata`（它本来就是"内容元数据"的归属，且已有两条领域边），全局搜索由 spec §4.2 早已声明、本计划才创建的 `web` 模块用一个薄控制器分区返回。分享链接的令牌校验住在 `library`（只存标量 id，不引领域类型），免登录端点则由两个领域模块各自提供，`video` 与 `image` 互不依赖的约束原样保持。上传独立成 `upload` 模块，合并走任务表。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · PostgreSQL 17（`pg_trgm` + `tsvector`）· HMAC-SHA256（分享票据）

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`（覆盖 §5.4 全局搜索、§6.2 `tag`/`share_link`/`upload_*`、§6.5 收藏、§7.6 分片上传、§7.7 搜索、路线图 P10–P11）

**前置计划:** 01 基础设施（**已执行完毕**）、02 扫描、03 视频域、04 图片域、05 预览与元数据 必须全部完成且 `mvn verify` 通过。

---

## Global Constraints

**继承计划 01–05 的全部 Global Constraints。执行前必须先读一遍计划 01 与计划 05 的该章节**
（尤其是计划 05 里 **Boot 4.1 用 Jackson 3（`tools.jackson.databind`）** 那一节，本计划同样解析 JSON）。

本计划新增：

### 迁移编号：V12–V16，一个特性一支，一支只由一个任务创建

| 文件 | 内容 | 由谁创建 |
|---|---|---|
| `V12__search_columns.sql` | `search_vector` 生成列与两类索引 | Task 1 |
| `V13__tags.sql` | `tag` 与两张关联表 | Task 5 |
| `V14__favorites.sql` | `video_favorite`、`image_favorite` | Task 7 |
| `V15__share_link.sql` | `share_link` | Task 8 |
| `V16__upload.sql` | `upload_session`、`upload_chunk` | Task 10 |

拆到这个粒度不是洁癖：**两个任务往同一支新迁移里追加内容，第二个任务就必须改一支已经跑过的迁移**，
而 Flyway 会因为校验和不符直接拒绝启动。一支迁移只属于一个任务，执行顺序就永远是对的。

计划 01 用 V1–V4（含补丁 `V1_1`），02 用 V5，03 用 V6–V7，04 用 V8–V9，05 用 V10–V11。**V17 起留给计划 07/08。**

### ⚠ pg_trgm 的中文边界（实测，spec §7.7 的说法需要按此修正）

环境 `postgres:17`，`CREATE EXTENSION pg_trgm`。以下每一行都是实跑结果：

| 表达式 | 结果 |
|---|---|
| `similarity('进击的巨人','进击的巨人')` | `1` |
| `similarity('进击的巨人','进击的巨人 最终季')` | `0.6` |
| **`similarity('进击的巨人','巨人')`** | **`0.125`** |
| `similarity('进击的巨人','夏目友人帐')` | `0` |
| `pg_trgm.similarity_threshold` 默认 | `0.3` |
| **`'进击的巨人' % '巨人'`** | **`false`** |
| `'进击的巨人' % '进击的'` | `true` |
| `word_similarity('巨人','进击的巨人')` / 阈值 | `0.333` / `0.6` → `<%` 也是 `false` |

**结论一：`%` 相似度操作符不能当中文子串搜索的匹配谓词。** 搜"巨人"匹配不到"进击的巨人"——
不是分词失败，是相似度归一化把短查询压到了阈值以下。**匹配谓词一律用 `ILIKE '%q%'`。**

10 万行表 + `gin (title gin_trgm_ops)`，`EXPLAIN ANALYZE` 实测：

| 查询 | 索引扫描返回 | Recheck 移除 | 耗时 |
|---|---|---|---|
| `ILIKE '%进击的%'`（3 字） | 2 | 0 | **0.21 ms** |
| `ILIKE '%巨人%'`（2 字） | 20007（全部） | 20004 | **29 ms** |
| `ILIKE '%巨%'`（1 字） | 全部 | 全部 | 29 ms |
| 强制顺序扫描对照（2 字） | — | — | 28.6 ms |

**结论二：查询串少于 3 个字符时 GIN trgm 索引不提供任何过滤**，退化成全表扫描 + recheck，
耗时与顺序扫描持平（索引不帮忙，也不拖累）。原因：pg_trgm 只能从 `%…%` 模式里提取
**完全包含在模式内**的三元组，两个中文字符提不出。

**而两字恰恰是中文最常见的查询长度（巨人 / 夏目 / 鬼灭）。**

**本计划的决定：接受这个上界。** 10 万条目 29 ms 对单实例自托管媒体库完全够用。
理由与退路写进 ADR-006——**"我实测过它的边界在哪儿"比"我用了 pg_trgm 支持中文"有价值得多**。
将来若真不够，收敛方向是 `pg_bigm`，代价是自建 PostgreSQL 镜像、破坏"一键启动"的交付目标。

### tsvector 只对拉丁文有用，但那份用处是真的

同一张表加生成列 `to_tsvector('english', …)` + GIN 后实测：

| 查询 | `search_vector @@ plainto_tsquery` | `ILIKE '%q%'` |
|---|---|---|
| `bunny` | 命中 `Big Buck Bunny` **和 `The Bunnies Are Running`** | 只命中前者 |
| `movies` | 命中 `Sintel the Movie` | **0 行** |
| `巨人` | 0 行 | 3 行 |

`to_tsvector('english','进击的巨人')` = `'进击的巨人':1`（整块，不分词）。

**两条路径各管各的**：三元组管中文子串，tsvector 管拉丁文的词干化与相关度。
spec §7.7「二者结果合并排序」的设计成立，本计划照做。

### ILIKE 模式必须转义

用户输入里的 `%`、`_`、`\` 在 `LIKE` 里是元字符。搜 `50%` 若不转义会变成"以 50 开头的任意串"。
**一律经 `SearchQuery.likePattern()` 转义并配 `ESCAPE '\'`**，Task 1 有专门的单元测试。
这既是正确性问题，也是"用户输入不能直接拼进模式"的一般纪律。

### 分片上传不用 multipart，用 `application/octet-stream`

Boot 4 的 multipart 属性没有改名（仍是 `spring.servlet.multipart.*`，声明在
`spring-boot-servlet-4.1.0.jar`，经 `spring-boot-starter-webmvc` → `spring-boot-webmvc` →
`spring-boot-servlet` 传递引入，`mvn dependency:tree` 确认为 `compile`，**不需要新增依赖**）。
但它的默认值对分片上传是致命的：

| 属性 | 默认值 |
|---|---|
| `spring.servlet.multipart.max-file-size` | **1MB** |
| `spring.servlet.multipart.max-request-size` | **10MB** |
| `spring.servlet.multipart.file-size-threshold` | `0B` |

**本计划的分片体走原始 `application/octet-stream` 请求体，直接从
`HttpServletRequest.getInputStream()` 流式落盘**，于是：

- 不受上面三个限制约束，不需要为了上传去调全局配置。
- 不经过 multipart 解析器，**分片内容一个字节都不进内存**。
- 请求体就是分片本身，没有边界解析开销。

分片大小由服务端在创建会话时决定并下发（`mymedia.upload.chunk-size`，默认 8MB）。

### `SampledHash` 提升到 `shared`

计划 02 把它定义成 `com.mymedia.scan` 的 package-private 类，而秒传与合并后校验都要用它。
**Task 10 把它移到 `com.mymedia.shared` 并改为 public**，与 `NaturalSortKey`、`MaterializedPath`
并列——它是纯算法、不带 `scan` 的任何状态，正好符合项目既有的
**「复用算法，不复用模型」**惯例。

算法本身一个字节都不能改（改了会让既有 `content_hash` 全部失效）：

```
SHA-256( 8 字节大端 sizeBytes ‖ 采样区 )
采样区 = size ≤ 2MB ? 整个文件 : 首 1MB ‖ 尾 1MB
输出 = 小写十六进制 64 字符
```

### 秒传的边界（已定稿）

`scanned_file.content_hash` **绝大多数是 NULL**：计划 02 只在"消失数与新增数都非零"时才算哈希，
索引也是 `WHERE content_hash IS NOT NULL` 的部分索引。因此秒传分两步：

1. 按 `content_hash` 直接查——命中即秒传。
2. 未命中时，取同库内 `size_bytes` 相同且 `content_hash IS NULL` 的候选（上限 8 个），
   **现算它们的哈希并写回**，再比。

第 2 步让秒传对存量文件也有效，而且**算过的哈希会留下**——秒传尝试顺带把哈希补齐了，
下次更快。代价是最多 8 次 2MB 读。写进 ADR-007。

### 分享链接：能力令牌，不是会话

- 令牌本身就是凭证（bearer capability），`/api/share/{token}/**` 整段 `permitAll`。
- **无效 / 过期 / 已撤销的令牌一律返回 404**，不区分"不存在"与"已失效"——
  区分它们等于告诉扫链接的人"这个令牌曾经存在"。
- 带密码的链接：`POST /api/share/{token}/unlock` 校验密码后签发一张
  **HMAC-SHA256 票据**，客户端后续请求带 `X-Share-Ticket` 头。
  **不用会话、也不每次重验 bcrypt**——一本漫画翻 20 页就是 20 次 bcrypt，
  每次约 100ms，阅读器会卡死。票据是无状态的，随链接一起过期。

### 模块边界

本计划**不新开自造模块**。唯一新增的 `web` 是 spec §4.2 早已声明、只是一直没创建的那个：

```
web      → shared, user, library, video, image      全局搜索（唯一的跨域端点）
upload   → shared, user, library, scan, jobs        分片上传
metadata → 新增标签（它已有 video / image 两条边）
library  → 新增 share_link（只存标量 id，不引领域类型，依赖不变）
video    → 新增搜索、收藏、分享端点
image    → 新增搜索、收藏、分享端点
```

`video` 与 `image` **依旧互不依赖**，由 `ModularityTests` 强制。

**核对过一遍：计划 05 Task 6 给两个域写死的 `allowedDependencies` 本计划一个字都不用改。**
本计划给两个域新增的搜索、收藏、分享端点只用到 `shared`、`user`、`library`，全在既有的允许列表里；
分享令牌的解析住在 `library`，两个域调用的是它的公开 API，不是对方的。

---

## File Structure

> 标了「Modify」的是已有文件，其余为新建。每一行末尾的说明就是这个文件唯一的职责。

```
src/main/java/com/mymedia/shared/
├── SampledHash.java                  从 scan 移入，改 public（Task 10）
└── SearchQuery.java                  查询规范化 + LIKE 转义（纯逻辑）

src/main/java/com/mymedia/video/
├── VideoSearchHit.java               public record：一条搜索命中
├── VideoSearchService.java           public API：双路径搜索
├── VideoFavorite.java                实体 → video_favorite
├── VideoFavoriteRepository.java      package-private
├── VideoFavoriteService.java         public API
├── VideoItem.java                    Modify：补只读的 coverAssetId 映射（Task 6）
├── VideoCatalogService.java          Modify：新增 findByIds（Task 6）
├── VideoStreamService.java           Modify：新增 locateForShare（Task 9）
└── web/
    ├── VideoSearchController.java    GET /api/video/search
    ├── VideoFavoriteController.java  收藏端点
    ├── VideoShareLinkController.java POST /api/video/items/{id}/share（需登录）
    ├── VideoShareController.java     GET /api/share/{token}/video/**（免登录）
    ├── VideoRangeResponder.java      package-private：Range 应答，两个入口共用
    └── VideoStreamController.java    Modify：改用 VideoRangeResponder（Task 9）

src/main/java/com/mymedia/image/
├── ImageSearchHit.java               public record：一条搜索命中
├── ImageSearchService.java           public API：双路径搜索
├── ImageFavorite.java                实体 → image_favorite
├── ImageFavoriteRepository.java      package-private
├── ImageFavoriteService.java         public API
├── ImageCatalogService.java          Modify：新增 findByIds（Task 6）
├── ImagePageService.java             Modify：新增 locateForShare（Task 9）
└── web/
    ├── ImageSearchController.java    GET /api/image/search
    ├── ImageFavoriteController.java  收藏端点
    ├── ImageShareLinkController.java POST /api/image/nodes/{id}/share（需登录）
    └── ImageShareController.java     GET /api/share/{token}/image/**（免登录）

src/main/java/com/mymedia/metadata/
├── Tag.java                          实体 → tag
├── TagRepository.java                package-private
├── TagSlug.java                      package-private：slug 生成（纯逻辑）
├── TagLinkStore.java                 package-private：两张关联表的 JdbcTemplate 读写
├── TagService.java                   public API：增删查、打标签、按标签列条目
└── web/
    ├── TagDto.java
    ├── TagController.java            /api/tags/**
    └── TagLinkController.java        /api/video|image/**/tags、/api/tags/{id}/items

src/main/java/com/mymedia/library/
├── ShareLink.java                    实体 → share_link（只存标量 id）
├── ShareLinkRepository.java          package-private
├── ShareGrant.java                   public record：令牌解析结果
├── ShareLinkDto.java                 public：请求体与响应体（两个域的控制器也要用）
├── ShareTicket.java                  package-private：HMAC 票据签发与校验
├── ShareLinkService.java             public API：创建 / 撤销 / 解析 / 解锁
├── ShareLinkController.java          package-private：/api/shares（需登录）
└── ShareAccessController.java        package-private：/api/share/{token}（免登录）

src/main/java/com/mymedia/scan/
├── ScannedFileHashService.java       public API：指纹查询与按需补算（Task 10）
├── ScannedFileRepository.java        Modify：两个新查询（Task 10）
└── RelocationDetector.java           Modify：SampledHash 换包后补 import（Task 10）

src/main/java/com/mymedia/user/
└── SecurityConfig.java               Modify：放行 /api/share/**（Task 9）

src/main/java/com/mymedia/web/
├── package-info.java                 @ApplicationModule("Web")
├── GlobalSearchDto.java
└── GlobalSearchController.java       GET /api/search（唯一的跨域端点）

src/main/java/com/mymedia/upload/
├── package-info.java                 @ApplicationModule("Upload")
├── UploadProperties.java             package-private：@ConfigurationProperties
├── UploadStatus.java                 public 枚举
├── UploadSession.java                实体 → upload_session
├── UploadSessionRepository.java      package-private：含原子状态跃迁
├── UploadChunkStore.java             package-private：upload_chunk 的 JdbcTemplate 读写
├── UploadStorage.java                package-private：临时分片目录布局与合并
├── InstantUploadResolver.java        package-private：秒传判定（含同尺寸候选现算）
├── UploadSessionService.java         public API：创建 / 查询 / 收分片
├── UploadAssembler.java              package-private：合并 + 校验 + 入库
├── UploadAssembleJobHandler.java     package-private：UPLOAD_ASSEMBLE
├── SafeFileName.java                 package-private：文件名净化（纯逻辑）
└── web/
    ├── UploadDto.java
    └── UploadController.java         /api/upload/**

src/main/resources/
├── application.yml                   Modify：mymedia.share.* 与 mymedia.upload.*（Task 10）
└── db/migration/
    ├── V12__search_columns.sql
    ├── V13__tags.sql
    ├── V14__favorites.sql
    ├── V15__share_link.sql
    └── V16__upload.sql

src/test/java/com/mymedia/shared/
├── SearchQueryTest.java              纯单元
├── SearchSchemaTest.java             集成：生成列与四个索引真的建出来了
└── SampledHashTest.java              从 scan 移入（Task 10）

src/test/java/com/mymedia/video/
├── VideoSearchServiceTest.java       集成
├── VideoFavoriteServiceTest.java     集成
└── VideoShareControllerTest.java     集成

src/test/java/com/mymedia/image/
├── ImageSearchServiceTest.java       集成
├── ImageFavoriteServiceTest.java     集成
└── ImageShareControllerTest.java     集成

src/test/java/com/mymedia/metadata/
├── TagSlugTest.java                  纯单元
├── TagServiceTest.java               集成
└── TagLinkTest.java                  集成

src/test/java/com/mymedia/library/
├── ShareTicketTest.java              纯单元
├── ShareLinkServiceTest.java         集成
└── ShareLinkControllerTest.java      集成：只经 HTTP，不引用任何领域类型

src/test/java/com/mymedia/web/
└── GlobalSearchControllerTest.java   集成

src/test/java/com/mymedia/upload/
├── SafeFileNameTest.java             纯单元
├── InstantUploadResolverTest.java    集成
├── UploadSessionServiceTest.java     集成
├── UploadChunkTest.java              集成：收片、乱序、重传、越界
└── UploadEndToEndTest.java           集成：分片 → 断点续传 → 合并 → 入库 → 扫描

docs/adr/
├── ADR-006-中文搜索的真实边界.md
└── ADR-007-采样哈希与秒传的边界.md

docs/walkthrough/
└── 06-检索与上传.md
```

---

## Task 1: 搜索列、索引与查询规范化

**Files:**
- Create: `src/main/resources/db/migration/V12__search_columns.sql`
- Create: `src/main/java/com/mymedia/shared/SearchQuery.java`
- Test: `src/test/java/com/mymedia/shared/SearchQueryTest.java`
- Test: `src/test/java/com/mymedia/shared/SearchSchemaTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（计划 01 Task 4）
- Produces:
  - `public record SearchQuery(String normalized, String lowered, String likePattern)`
    - `public static SearchQuery of(String raw)` —— 空白输入抛 `IllegalArgumentException`
    - `public boolean usesTrigramIndex()` —— 规范化后**码点数** ≥ 3
  - 数据库：`video_item.search_vector`、`image_node.search_vector` 两个生成列 + 四个索引

### 生成列必须用双参数的 `to_tsvector`

`to_tsvector(text)` 是 **STABLE**（结果依赖 `default_text_search_config` 这个会话设置），
PostgreSQL 不允许 STABLE 函数出现在生成列里。`to_tsvector(regconfig, text)` 才是 **IMMUTABLE**。
写成单参数形式会在迁移时报 `generation expression is not immutable`——这是必踩的坑，
所以下面的 SQL 一律写 `to_tsvector('english', …)`。

- [ ] **Step 1: 写会失败的规范化单元测试**

`src/test/java/com/mymedia/shared/SearchQueryTest.java`：

```java
package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        SearchQuery query = SearchQuery.of("  进击的   巨人 ");

        assertThat(query.normalized()).isEqualTo("进击的 巨人");
    }

    @Test
    void loweredIsUsedForSimilarityScoringSoLatinCaseDoesNotHurtRanking() {
        // similarity() 是大小写敏感的，Big 与 big 切出来的三元组不同
        assertThat(SearchQuery.of("Big Buck Bunny").lowered()).isEqualTo("big buck bunny");
    }

    @Test
    void wrapsPatternInWildcardsForSubstringMatching() {
        assertThat(SearchQuery.of("巨人").likePattern()).isEqualTo("%巨人%");
    }

    @Test
    void escapesPercentSoItIsNotTreatedAsAWildcard() {
        // 搜 "50%" 若不转义会变成"以 50 开头的任意串"
        assertThat(SearchQuery.of("50%").likePattern()).isEqualTo("%50\\%%");
    }

    @Test
    void escapesUnderscore() {
        assertThat(SearchQuery.of("a_b").likePattern()).isEqualTo("%a\\_b%");
    }

    @Test
    void escapesBackslashFirstSoEscapingIsNotDoubled() {
        // 反斜杠必须最先转义，否则后面转出来的反斜杠会被再转一次
        assertThat(SearchQuery.of("a\\b").likePattern()).isEqualTo("%a\\\\b%");
    }

    @Test
    void reportsWhetherTheTrigramIndexCanHelp() {
        // 实测：少于 3 个字符时 GIN trgm 索引提取不出三元组，退化成全表扫描
        assertThat(SearchQuery.of("进击的").usesTrigramIndex()).isTrue();
        assertThat(SearchQuery.of("巨人").usesTrigramIndex()).isFalse();
        assertThat(SearchQuery.of("巨").usesTrigramIndex()).isFalse();
    }

    @Test
    void countsCodePointsNotCharsSoAstralSymbolsAreNotMiscounted() {
        // 三个 emoji 是 6 个 char、3 个码点
        assertThat(SearchQuery.of("😀😀😀").usesTrigramIndex()).isTrue();
        assertThat(SearchQuery.of("😀😀").usesTrigramIndex()).isFalse();
    }

    @Test
    void rejectsBlankInputInsteadOfMatchingEverything() {
        // 空查询若放行，likePattern 会是 '%%'，等于把整个库倒出来
        assertThatThrownBy(() -> SearchQuery.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchQuery.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 写会失败的 schema 测试**

`src/test/java/com/mymedia/shared/SearchSchemaTest.java`：

```java
package com.mymedia.shared;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSchemaTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private List<String> indexNames(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }

    @Test
    void videoItemHasAGeneratedTsvectorColumn() {
        String generated = jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                 WHERE table_name = 'video_item' AND column_name = 'search_vector'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void imageNodeHasAGeneratedTsvectorColumn() {
        String generated = jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                 WHERE table_name = 'image_node' AND column_name = 'search_vector'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void bothSearchPathsAreIndexed() {
        assertThat(indexNames("video_item"))
                .contains("idx_video_item_title_trgm",        // 计划 03 建的
                          "idx_video_item_original_trgm",     // 本任务补的
                          "idx_video_item_fts");
        assertThat(indexNames("image_node"))
                .contains("idx_image_node_name_trgm",         // 计划 04 建的
                          "idx_image_node_title_trgm",        // 本任务补的
                          "idx_image_node_fts");
    }

    @Test
    void generatedColumnFollowsTheTitleWithoutAnyTrigger() {
        Long libraryId = jdbc.queryForObject("""
                INSERT INTO libraries (name, domain, root_path)
                VALUES ('搜索用库' || gen_random_uuid(), 'VIDEO', '/tmp/' || gen_random_uuid())
                RETURNING id
                """, Long.class);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, summary)
                VALUES (?, 'MOVIE', 'Big Buck Bunny', 'big buck bunny', 'A rabbit story')
                RETURNING id
                """, Long.class, libraryId);

        // 词干化：查 bunnies 能命中 Bunny
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM video_item
                 WHERE id = ? AND search_vector @@ plainto_tsquery('english','bunnies')
                """, Integer.class, itemId)).isEqualTo(1);

        jdbc.update("UPDATE video_item SET title = 'Sintel' WHERE id = ?", itemId);

        // 生成列自动跟随，不需要触发器
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM video_item
                 WHERE id = ? AND search_vector @@ plainto_tsquery('english','sintel')
                """, Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void chineseStaysOneTokenWhichIsWhyTrigramsAreTheMainPath() {
        // 这条断言是 ADR-006 的证据：tsvector 对中文无能为力
        assertThat(jdbc.queryForObject(
                "SELECT to_tsvector('english','进击的巨人')::text", String.class))
                .isEqualTo("'进击的巨人':1");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='SearchQueryTest,SearchSchemaTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol|Tests run" t.log | head -10
```

Expected: 编译失败（`SearchQuery` 不存在）；schema 测试因为列与索引不存在而失败。

- [ ] **Step 4: 写迁移脚本**

`src/main/resources/db/migration/V12__search_columns.sql`：

```sql
-- ============================================================
-- 搜索的两条路径（spec 7.7）：
--   中文主路径 —— pg_trgm 三元组索引 + ILIKE 子串匹配
--   拉丁文路径 —— tsvector 生成列，提供词干化与相关度排序
-- 两条路径各管各的，查询时取并集、分层排序。见 ADR-006。
--
-- ⚠ 生成列里必须用双参数的 to_tsvector(regconfig, text)：
--   单参数版本是 STABLE（依赖 default_text_search_config 会话设置），
--   PostgreSQL 会拒绝，报 "generation expression is not immutable"。
-- ============================================================

ALTER TABLE video_item ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english',
        coalesce(title, '') || ' ' ||
        coalesce(original_title, '') || ' ' ||
        coalesce(summary, ''))) STORED;

CREATE INDEX idx_video_item_fts ON video_item USING gin (search_vector);

-- 计划 03 只给 title 建了三元组索引，原名同样要能搜
CREATE INDEX idx_video_item_original_trgm
    ON video_item USING gin (original_title gin_trgm_ops);

ALTER TABLE image_node ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english',
        coalesce(title, '') || ' ' ||
        coalesce(name, '') || ' ' ||
        coalesce(summary, ''))) STORED;

CREATE INDEX idx_image_node_fts ON image_node USING gin (search_vector);

-- 计划 04 只给 name 建了三元组索引；刮削回来的 title 也要能搜
CREATE INDEX idx_image_node_title_trgm
    ON image_node USING gin (title gin_trgm_ops);
```

- [ ] **Step 5: 实现查询规范化**

`src/main/java/com/mymedia/shared/SearchQuery.java`：

```java
package com.mymedia.shared;

/**
 * 一次搜索输入的规范化结果。
 *
 * <p>存在的理由有两个，都不是"整洁"：
 * <ol>
 *   <li><b>转义。</b> {@code %}、{@code _}、{@code \} 在 LIKE 里是元字符。
 *       用户搜 {@code 50%} 若原样拼进模式，会变成"以 50 开头的任意串"——
 *       既是错误结果，也是"用户输入不能直接拼进模式"这条纪律的反例。</li>
 *   <li><b>大小写。</b> 匹配用 {@code ILIKE}（本来就不分大小写），
 *       但排序用的 {@code similarity()} <b>是</b>大小写敏感的：
 *       {@code Big} 与 {@code big} 切出来的三元组不同。所以另留一个小写副本给打分用。</li>
 * </ol>
 *
 * <p>纯逻辑、无依赖，因此它的测试是纯单元测试。
 */
public record SearchQuery(String normalized, String lowered, String likePattern) {

    /** 少于这个码点数时，GIN trgm 索引提取不出三元组（实测，见 ADR-006）。 */
    private static final int TRIGRAM_MIN_LENGTH = 3;

    public static SearchQuery of(String raw) {
        if (raw == null || raw.isBlank()) {
            // 放行空查询会得到 '%%' 模式，等于把整个库倒出来
            throw new IllegalArgumentException("搜索词不能为空");
        }
        String normalized = raw.trim().replaceAll("\\s+", " ");
        return new SearchQuery(normalized,
                normalized.toLowerCase(java.util.Locale.ROOT),
                "%" + escapeLike(normalized) + "%");
    }

    /**
     * 这次查询能不能用上三元组索引。
     *
     * <p><b>不改变行为，只用于日志与讲解</b>：少于 3 个码点时索引不提供任何过滤，
     * 查询退化成全表扫描 + recheck（10 万行实测 29ms，与顺序扫描持平）。
     * 而两个字恰恰是中文最常见的查询长度——这个事实值得被记录下来，
     * 而不是等到某天有人问"为什么搜两个字慢"时再去猜。
     */
    public boolean usesTrigramIndex() {
        return normalized.codePointCount(0, normalized.length()) >= TRIGRAM_MIN_LENGTH;
    }

    /** 反斜杠必须最先转义，否则后面转出来的反斜杠会被再转一次。 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='SearchQueryTest,SearchSchemaTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`SearchQueryTest` 9 个、`SearchSchemaTest` 5 个用例通过。

- [ ] **Step 7: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V12__search_columns.sql \
        src/main/java/com/mymedia/shared/SearchQuery.java \
        src/test/java/com/mymedia/shared
git commit -m "feat: 添加搜索生成列、索引与查询规范化

两条路径：pg_trgm 三元组管中文子串，tsvector 生成列管拉丁文词干化。
生成列必须用双参数 to_tsvector——单参数版本是 STABLE，PostgreSQL 会拒绝。

SearchQuery 负责 LIKE 转义：搜 '50%' 若不转义会变成'以 50 开头的任意串'。
usesTrigramIndex 记录了'少于 3 个码点索引不起作用'这个实测事实。"
```

---

## Task 2: 视频域搜索

**Files:**
- Create: `src/main/java/com/mymedia/video/VideoSearchHit.java`
- Create: `src/main/java/com/mymedia/video/VideoSearchService.java`
- Create: `src/main/java/com/mymedia/video/web/VideoSearchController.java`
- Test: `src/test/java/com/mymedia/video/VideoSearchServiceTest.java`

**Interfaces:**
- Consumes: `SearchQuery`（Task 1）、`LibraryAccessService`、`MediaLibrary`、`LibraryDomain`（计划 01）、`UserQueryService`（计划 01）
- Produces:
  - `public record VideoSearchHit(Long itemId, Long libraryId, String title, String sortTitle, Long coverAssetId, double score)`
  - `public class VideoSearchService` — `public List<VideoSearchHit> search(Long userId, SearchQuery query, int limit)`
  - `GET /api/video/search?q=&limit=`

### 排序为什么是分层的，而不是把两个分数加起来

`similarity()` 落在 0–1，`ts_rank()` 通常是 0.0X 量级——**两个分数不在一个尺度上**，
加权求和只是把"我不知道怎么比"包装成一个数字。改用分层：

```
1. 子串命中的排前面        —— 用户打出一个标题时，期待的就是子串匹配
2. 同为子串命中的按相似度   —— similarity() 在这一层内部是可比的
3. 剩下的（纯 tsvector 命中）按 ts_rank
4. 最后按 sort_title 稳定排序
```

每一层内部的比较都是有意义的，层与层之间是优先级而不是数值——这个结构能讲清楚。

### 为什么用 `NamedParameterJdbcTemplate`

搜索 SQL 里同一个模式要出现三次、同一个查询词要出现四次。位置参数版本会变成一串
`query.likePattern(), query.likePattern(), query.lowered(), …`，数错一个就是运行时错误。
命名参数还顺带解决了 `IN (:libraryIds)` 的集合展开——Spring 会自己展开成正确数量的占位符。

**可访问库为空时必须提前返回**：`IN ()` 是语法错误。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoSearchServiceTest.java`：

```java
package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoSearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    VideoSearchService searchService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long userId;

    /** 搜索测的是 SQL，不是扫描链路，所以直接插行造数据。 */
    private Long insertItem(Long libraryId, String title, String originalTitle, String summary) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, original_title, summary)
                VALUES (?, 'MOVIE', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, libraryId, title, title, originalTitle, summary);
    }

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
    }

    @BeforeEach
    void setUp() {
        library = newLibrary();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    private List<String> titles(String q) {
        return searchService.search(userId, SearchQuery.of(q), 20).stream()
                .map(VideoSearchHit::title)
                .toList();
    }

    @Test
    void findsChineseByTwoCharacterSubstring() {
        insertItem(library.getId(), "进击的巨人", null, null);
        insertItem(library.getId(), "夏目友人帐", null, null);

        // 这是整条搜索链路存在的理由：相似度只有 0.125，% 操作符匹配不到，
        // 必须走 ILIKE 子串
        assertThat(titles("巨人")).containsExactly("进击的巨人");
    }

    @Test
    void findsBySingleCharacterToo() {
        insertItem(library.getId(), "进击的巨人", null, null);

        assertThat(titles("巨")).containsExactly("进击的巨人");
    }

    @Test
    void findsLatinByStemmingWhichSubstringMatchingWouldMiss() {
        insertItem(library.getId(), "The Bunnies Are Running", null, null);

        // ILIKE '%bunny%' 匹配不到 Bunnies，tsvector 路径能
        assertThat(titles("bunny")).containsExactly("The Bunnies Are Running");
    }

    @Test
    void substringHitsOutrankFtsOnlyHits() {
        insertItem(library.getId(), "The Bunnies Are Running", null, null);
        insertItem(library.getId(), "Bunny Hop", null, null);

        // 打出 bunny 的人期待的是标题里真有 bunny 的那个
        assertThat(titles("bunny")).containsExactly("Bunny Hop", "The Bunnies Are Running");
    }

    @Test
    void ranksCloserTitlesFirstAmongSubstringHits() {
        insertItem(library.getId(), "进击的巨人 最终季 完结篇 特别版", null, null);
        insertItem(library.getId(), "进击的巨人", null, null);

        assertThat(titles("进击的巨人").get(0)).isEqualTo("进击的巨人");
    }

    @Test
    void searchesOriginalTitleAsWell() {
        insertItem(library.getId(), "大雄兔", "Big Buck Bunny", null);

        assertThat(titles("Buck")).containsExactly("大雄兔");
    }

    @Test
    void searchesSummaryThroughTheFtsPathOnly() {
        insertItem(library.getId(), "无名短片", null, "A rabbit and three rodents");

        assertThat(titles("rodents")).containsExactly("无名短片");
    }

    @Test
    void neverReturnsItemsFromLibrariesTheUserCannotAccess() {
        MediaLibrary other = newLibrary();
        insertItem(other.getId(), "进击的巨人", null, null);
        insertItem(library.getId(), "巨人族的新娘", null, null);

        assertThat(titles("巨人")).containsExactly("巨人族的新娘");
    }

    @Test
    void userWithNoAccessibleLibrariesGetsEmptyResultNotAnError() {
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        insertItem(library.getId(), "进击的巨人", null, null);

        // IN () 是语法错误，必须在进 SQL 之前就短路
        assertThat(searchService.search(stranger.getId(), SearchQuery.of("巨人"), 20)).isEmpty();
    }

    @Test
    void treatsPercentAsALiteralNotAWildcard() {
        insertItem(library.getId(), "折扣 50% 纪录片", null, null);
        insertItem(library.getId(), "折扣年代", null, null);

        assertThat(titles("50%")).containsExactly("折扣 50% 纪录片");
    }

    @Test
    void respectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            insertItem(library.getId(), "巨人系列 " + i, null, null);
        }

        assertThat(searchService.search(userId, SearchQuery.of("巨人"), 3)).hasSize(3);
    }

    @Test
    void adminSeesEveryLibraryWithoutExplicitGrants() {
        UserAccount admin = registrationService.register(
                "a" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.ADMIN);
        MediaLibrary other = newLibrary();
        insertItem(other.getId(), "只有管理员能看到的巨人", null, null);

        assertThat(searchService.search(admin.getId(), SearchQuery.of("巨人"), 20))
                .extracting(VideoSearchHit::title)
                .contains("只有管理员能看到的巨人");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoSearchServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`VideoSearchService` 不存在。

- [ ] **Step 3: 实现搜索服务**

`src/main/java/com/mymedia/video/VideoSearchHit.java`：

```java
package com.mymedia.video;

/**
 * 一条视频搜索结果。
 *
 * @param score 分层排序里的**首要分数**（子串命中时是三元组相似度，否则是 ts_rank）。
 *              只用于展示与调试；真正的顺序由 SQL 的 ORDER BY 决定，
 *              不要在 Java 侧拿它重排。
 */
public record VideoSearchHit(
        Long itemId,
        Long libraryId,
        String title,
        String sortTitle,
        Long coverAssetId,
        double score) {
}
```

`src/main/java/com/mymedia/video/VideoSearchService.java`：

```java
package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 视频域搜索：三元组子串 + tsvector 全文，取并集、分层排序。
 *
 * <p><b>排序为什么是分层的</b>：{@code similarity()} 落在 0–1，{@code ts_rank()} 是 0.0X 量级，
 * 两个分数不在一个尺度上，加权求和只是把"我不知道怎么比"包装成一个数字。分层之后
 * 每一层内部的比较都有意义，层与层之间是优先级：
 * 子串命中 → 相似度 → ts_rank → sort_title 兜底。
 *
 * <p><b>中文两字查询会走全表扫描</b>（实测 10 万行 29ms，与顺序扫描持平）——
 * pg_trgm 从 {@code %..%} 模式里提不出完整三元组。这是已知且接受的上界，见 ADR-006。
 */
@Service
public class VideoSearchService {

    private static final Logger log = LoggerFactory.getLogger(VideoSearchService.class);

    private static final String SQL = """
            SELECT vi.id, vi.library_id, vi.title, vi.sort_title, vi.cover_asset_id,
                   (vi.title ILIKE :pattern ESCAPE '\\'
                    OR vi.original_title ILIKE :pattern ESCAPE '\\') AS substring_hit,
                   greatest(similarity(lower(vi.title), :lowered),
                            similarity(lower(coalesce(vi.original_title, '')), :lowered))
                       AS trgm_score,
                   ts_rank(vi.search_vector, plainto_tsquery('english', :raw)) AS fts_score
              FROM video_item vi
             WHERE vi.library_id IN (:libraryIds)
               AND (vi.title ILIKE :pattern ESCAPE '\\'
                    OR vi.original_title ILIKE :pattern ESCAPE '\\'
                    OR vi.search_vector @@ plainto_tsquery('english', :raw))
             ORDER BY substring_hit DESC, trgm_score DESC, fts_score DESC, vi.sort_title
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LibraryAccessService accessService;

    VideoSearchService(NamedParameterJdbcTemplate jdbc, LibraryAccessService accessService) {
        this.jdbc = jdbc;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<VideoSearchHit> search(Long userId, SearchQuery query, int limit) {
        List<Long> libraryIds = accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.VIDEO)
                .map(MediaLibrary::getId)
                .toList();
        if (libraryIds.isEmpty()) {
            // IN () 是语法错误，必须在进 SQL 之前短路
            return List.of();
        }

        if (!query.usesTrigramIndex()) {
            log.debug("搜索词 '{}' 不足 3 个码点，三元组索引不起作用，本次为全表扫描",
                    query.normalized());
        }

        Map<String, Object> parameters = new MapSqlParameterSource()
                .addValue("pattern", query.likePattern())
                .addValue("lowered", query.lowered())
                .addValue("raw", query.normalized())
                .addValue("libraryIds", libraryIds)
                .addValue("limit", limit)
                .getValues();

        return jdbc.query(SQL, parameters, (rs, rowNum) -> new VideoSearchHit(
                rs.getLong("id"),
                rs.getLong("library_id"),
                rs.getString("title"),
                rs.getString("sort_title"),
                (Long) rs.getObject("cover_asset_id"),
                rs.getBoolean("substring_hit")
                        ? rs.getDouble("trgm_score")
                        : rs.getDouble("fts_score")));
    }
}
```

- [ ] **Step 4: 写端点**

`src/main/java/com/mymedia/video/web/VideoSearchController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoSearchHit;
import com.mymedia.video.VideoSearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video/search")
class VideoSearchController {

    private static final int MAX_LIMIT = 100;

    private final VideoSearchService searchService;
    private final UserQueryService userQueryService;

    VideoSearchController(VideoSearchService searchService, UserQueryService userQueryService) {
        this.searchService = searchService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<VideoSearchHit> search(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam("q") String q,
                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        // SearchQuery.of 对空白输入抛 IllegalArgumentException，
        // GlobalExceptionHandler 会把它翻成 400
        return searchService.search(userId, SearchQuery.of(q), Math.clamp(limit, 1, MAX_LIMIT));
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='VideoSearchServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/video src/test/java/com/mymedia/video/VideoSearchServiceTest.java
git commit -m "feat: 添加视频域双路径搜索

三元组子串管中文、tsvector 管拉丁文词干化，取并集分层排序：
子串命中 -> 相似度 -> ts_rank -> sort_title。

不把两个分数加权求和——similarity 是 0-1、ts_rank 是 0.0X 量级，
不在一个尺度上，加起来只是把'不知道怎么比'包装成一个数字。

可访问库为空时提前返回：IN () 是语法错误。"
```

Expected: `EXIT=0`，`VideoSearchServiceTest` 12 个用例通过。

---

## Task 3: 图片域搜索

**Files:**
- Create: `src/main/java/com/mymedia/image/ImageSearchHit.java`
- Create: `src/main/java/com/mymedia/image/ImageSearchService.java`
- Create: `src/main/java/com/mymedia/image/web/ImageSearchController.java`
- Test: `src/test/java/com/mymedia/image/ImageSearchServiceTest.java`

**Interfaces:**
- Consumes: 同 Task 2
- Produces:
  - `public record ImageSearchHit(Long nodeId, Long libraryId, String name, String title, Long coverAssetId, int totalPageCount, boolean readable, double score)`
  - `public class ImageSearchService` — `public List<ImageSearchHit> search(Long userId, SearchQuery query, int limit)`
  - `GET /api/image/search?q=&limit=`

### 与视频域的两处刻意差异

1. **搜两个名字**：`name` 是目录/压缩包的原名（一定有），`title` 是刮削回来的标题（可能没有）。
   两个都要能搜到，展示时优先 `title`。视频域只有 `title` 这一个主名。
2. **只搜 ACTIVE 节点**：图片域的节点有 `status`，文件消失时标 `MISSING` 而不删（计划 02 的铁律）。
   搜索结果里出现一个点开就 404 的节点是很差的体验，所以加 `status = 'ACTIVE'` 这一条。
   视频域的 `video_item` 没有这个列，所以没有对应条件。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageSearchServiceTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImageSearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    ImageSearchService searchService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long userId;

    private Long insertNode(Long libraryId, String name, String status, int directPageCount) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', ?, ?)
                RETURNING id
                """, Long.class, libraryId, name, name, name, directPageCount, status);
    }

    private void setTitle(Long nodeId, String title) {
        jdbc.update("UPDATE image_node SET title = ? WHERE id = ?", title, nodeId);
    }

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
    }

    @BeforeEach
    void setUp() {
        library = newLibrary();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    private List<String> names(String q) {
        return searchService.search(userId, SearchQuery.of(q), 20).stream()
                .map(ImageSearchHit::name)
                .toList();
    }

    @Test
    void findsByDirectoryName() {
        insertNode(library.getId(), "某画师 2024 合集", "ACTIVE", 12);
        insertNode(library.getId(), "另一个画师", "ACTIVE", 3);

        assertThat(names("画师 2024")).containsExactly("某画师 2024 合集");
    }

    @Test
    void findsByScrapedTitleWhichTheDirectoryNameDoesNotContain() {
        Long nodeId = insertNode(library.getId(), "[Group] Vol.01 (2019)", "ACTIVE", 180);
        setTitle(nodeId, "进击的巨人");

        // 目录名是发布组的乱码风格，刮削回来的标题才是人认得的那个
        assertThat(names("巨人")).containsExactly("[Group] Vol.01 (2019)");
    }

    @Test
    void hidesNodesWhoseFilesHaveGoneMissing() {
        insertNode(library.getId(), "已下线的巨人画集", "MISSING", 5);
        insertNode(library.getId(), "在线的巨人画集", "ACTIVE", 5);

        // 搜出一个点开就 404 的节点是很差的体验
        assertThat(names("巨人")).containsExactly("在线的巨人画集");
    }

    @Test
    void reportsWhetherTheNodeIsReadable() {
        insertNode(library.getId(), "纯目录 巨人", "ACTIVE", 0);
        insertNode(library.getId(), "可读 巨人", "ACTIVE", 20);

        List<ImageSearchHit> hits = searchService.search(userId, SearchQuery.of("巨人"), 20);

        assertThat(hits).extracting(ImageSearchHit::readable)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void neverReturnsNodesFromLibrariesTheUserCannotAccess() {
        MediaLibrary other = newLibrary();
        insertNode(other.getId(), "别人的巨人", "ACTIVE", 1);
        insertNode(library.getId(), "我的巨人", "ACTIVE", 1);

        assertThat(names("巨人")).containsExactly("我的巨人");
    }

    @Test
    void userWithNoAccessibleLibrariesGetsEmptyResultNotAnError() {
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        insertNode(library.getId(), "巨人", "ACTIVE", 1);

        assertThat(searchService.search(stranger.getId(), SearchQuery.of("巨人"), 20)).isEmpty();
    }

    @Test
    void treatsUnderscoreAsALiteralNotAWildcard() {
        insertNode(library.getId(), "a_b 画集", "ACTIVE", 1);
        insertNode(library.getId(), "axb 画集", "ACTIVE", 1);

        assertThat(names("a_b")).containsExactly("a_b 画集");
    }

    @Test
    void findsLatinByStemming() {
        insertNode(library.getId(), "The Bunnies Collection", "ACTIVE", 30);

        assertThat(names("bunny")).containsExactly("The Bunnies Collection");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageSearchServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`ImageSearchService` 不存在。

- [ ] **Step 3: 实现搜索服务**

`src/main/java/com/mymedia/image/ImageSearchHit.java`：

```java
package com.mymedia.image;

/**
 * 一条图片搜索结果。
 *
 * @param name     目录或压缩包的原名，一定有
 * @param title    刮削回来的标题，可能为 {@code null}；展示时优先它
 * @param readable {@code direct_page_count > 0}，界面据此决定点进去是阅读器还是子项网格
 */
public record ImageSearchHit(
        Long nodeId,
        Long libraryId,
        String name,
        String title,
        Long coverAssetId,
        int totalPageCount,
        boolean readable,
        double score) {
}
```

`src/main/java/com/mymedia/image/ImageSearchService.java`：

```java
package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 图片域搜索。结构与视频域一致（三元组子串 + tsvector，分层排序），两处刻意不同：
 *
 * <ol>
 *   <li><b>搜两个名字</b>：{@code name} 是目录/压缩包原名（一定有），
 *       {@code title} 是刮削回来的标题（可能没有）。发布组风格的目录名与人认得的标题
 *       常常毫不相干，两个都要能搜到。</li>
 *   <li><b>只搜 ACTIVE 节点</b>：文件消失时扫描只标 {@code MISSING} 不删（计划 02 的铁律），
 *       但搜出一个点开就 404 的节点是很差的体验。</li>
 * </ol>
 */
@Service
public class ImageSearchService {

    private static final String SQL = """
            SELECT n.id, n.library_id, n.name, n.title, n.cover_asset_id,
                   n.total_page_count, n.direct_page_count,
                   (n.name ILIKE :pattern ESCAPE '\\'
                    OR n.title ILIKE :pattern ESCAPE '\\') AS substring_hit,
                   greatest(similarity(lower(n.name), :lowered),
                            similarity(lower(coalesce(n.title, '')), :lowered)) AS trgm_score,
                   ts_rank(n.search_vector, plainto_tsquery('english', :raw)) AS fts_score
              FROM image_node n
             WHERE n.library_id IN (:libraryIds)
               AND n.status = 'ACTIVE'
               AND (n.name ILIKE :pattern ESCAPE '\\'
                    OR n.title ILIKE :pattern ESCAPE '\\'
                    OR n.search_vector @@ plainto_tsquery('english', :raw))
             ORDER BY substring_hit DESC, trgm_score DESC, fts_score DESC, n.sort_key
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LibraryAccessService accessService;

    ImageSearchService(NamedParameterJdbcTemplate jdbc, LibraryAccessService accessService) {
        this.jdbc = jdbc;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<ImageSearchHit> search(Long userId, SearchQuery query, int limit) {
        List<Long> libraryIds = accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.IMAGE)
                .map(MediaLibrary::getId)
                .toList();
        if (libraryIds.isEmpty()) {
            return List.of();
        }

        Map<String, Object> parameters = new MapSqlParameterSource()
                .addValue("pattern", query.likePattern())
                .addValue("lowered", query.lowered())
                .addValue("raw", query.normalized())
                .addValue("libraryIds", libraryIds)
                .addValue("limit", limit)
                .getValues();

        return jdbc.query(SQL, parameters, (rs, rowNum) -> new ImageSearchHit(
                rs.getLong("id"),
                rs.getLong("library_id"),
                rs.getString("name"),
                rs.getString("title"),
                (Long) rs.getObject("cover_asset_id"),
                rs.getInt("total_page_count"),
                rs.getInt("direct_page_count") > 0,
                rs.getBoolean("substring_hit")
                        ? rs.getDouble("trgm_score")
                        : rs.getDouble("fts_score")));
    }
}
```

- [ ] **Step 4: 写端点**

`src/main/java/com/mymedia/image/web/ImageSearchController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageSearchHit;
import com.mymedia.image.ImageSearchService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/image/search")
class ImageSearchController {

    private static final int MAX_LIMIT = 100;

    private final ImageSearchService searchService;
    private final UserQueryService userQueryService;

    ImageSearchController(ImageSearchService searchService, UserQueryService userQueryService) {
        this.searchService = searchService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<ImageSearchHit> search(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam("q") String q,
                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        return searchService.search(userId, SearchQuery.of(q), Math.clamp(limit, 1, MAX_LIMIT));
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ImageSearchServiceTest,VideoSearchServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageSearchServiceTest.java
git commit -m "feat: 添加图片域双路径搜索

与视频域两处刻意不同：搜 name 与 title 两个名字（发布组风格的目录名
与人认得的标题常常毫不相干）；只搜 ACTIVE 节点（文件消失只标 MISSING
不删，但搜出一个点开就 404 的节点是很差的体验）。"
```

Expected: `EXIT=0`，`ImageSearchServiceTest` 8 个用例通过。

---

## Task 4: `web` 模块与全局搜索端点

**Files:**
- Create: `src/main/java/com/mymedia/web/package-info.java`
- Create: `src/main/java/com/mymedia/web/GlobalSearchDto.java`
- Create: `src/main/java/com/mymedia/web/GlobalSearchController.java`
- Test: `src/test/java/com/mymedia/web/GlobalSearchControllerTest.java`

**Interfaces:**
- Consumes: `VideoSearchService`、`VideoSearchHit`（Task 2）、`ImageSearchService`、`ImageSearchHit`（Task 3）、`SearchQuery`（Task 1）、`UserQueryService`（计划 01）
- Produces:
  - Modulith 模块 `web`，`allowedDependencies = {"shared", "user", "video", "image"}`
  - `GET /api/search?q=&limit=` —— **本项目唯一的跨域端点**

### 为什么值得为一个控制器建一个模块

`web` 不是本计划发明的：spec §4.2 的模块清单里它一直在（静态资源托管、全局异常处理、OpenAPI 文档），
只是前五份计划都还用不到。全局搜索是**第一个真正需要同时看见两个域的东西**，
而它不该被塞进 `video` 或 `image`（那会让其中一个依赖另一个，直接违背域分区）、
也不该塞进 `metadata`（搜索不是元数据）。

计划 07（前端静态资源）与计划 08（OpenAPI）会继续往这个模块里放东西，
所以它不会长期只有一个类。

### 结果分区，不混排

spec §5.4：「唯一交汇点是全局搜索，且**结果分区展示，不混排**」。
所以响应体是两个独立的数组，而不是一个带 `type` 字段的混合列表——
两个域的卡片布局、比例、可用操作都不一样，混排之后前端第一件事就是把它们再拆开。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/web/GlobalSearchControllerTest.java`：

```java
package com.mymedia.web;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GlobalSearchControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private String username;

    @BeforeEach
    void setUp() {
        MediaLibrary videoLibrary = libraryService.create(
                "视频库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        MediaLibrary imageLibrary = libraryService.create(
                "图片库" + UUID.randomUUID(), LibraryDomain.IMAGE, "/tmp/" + UUID.randomUUID());

        jdbc.update("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '进击的巨人 剧场版', '进击的巨人 剧场版')
                """, videoLibrary.getId());
        jdbc.update("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/巨人画集/', 0,
                        '巨人画集', '巨人画集', 'DIRECTORY', 40, 'ACTIVE')
                """, imageLibrary.getId());

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), videoLibrary.getId());
        accessService.grant(user.getId(), imageLibrary.getId());
    }

    @Test
    void returnsTwoPartitionedArraysRatherThanOneMixedList() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "巨人").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("巨人"))
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.video[0].title").value("进击的巨人 剧场版"))
                .andExpect(jsonPath("$.image[0].name").value("巨人画集"));
    }

    @Test
    void aDomainWithNoHitsIsAnEmptyArrayNotAMissingField() throws Exception {
        // 前端两个分区是常驻的，缺字段会让它多写一堆判空
        mockMvc.perform(get("/api/search").param("q", "剧场版").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void blankQueryIsARequestErrorNotAnEmptyResult() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "  ").with(httpBasic(username, "pw")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "巨人"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutAnyLibraryAccessSeesBothPartitionsEmpty() throws Exception {
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/search").param("q", "巨人").with(httpBasic(stranger, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(0)));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=GlobalSearchControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|Tests run|Status" t.log | head -5
```

Expected: 404——`/api/search` 端点不存在。

- [ ] **Step 3: 声明 `web` 模块**

`src/main/java/com/mymedia/web/package-info.java`：

```java
/**
 * 跨域的 Web 层：全局搜索，将来还有静态资源托管与 OpenAPI 文档（spec §4.2）。
 *
 * <p><b>全局搜索是本项目唯一需要同时看见两个域的东西</b>，因此也是唯一
 * 可以同时依赖 {@code video} 与 {@code image} 的地方。把它塞进任何一个领域模块
 * 都会让其中一个依赖另一个，直接违背域分区（spec §5.2）。
 *
 * <p>结果<b>分区返回、不混排</b>：两个域的卡片布局、比例、可用操作都不一样，
 * 混排之后前端第一件事就是把它们再拆开。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Web",
        allowedDependencies = {"shared", "user", "video", "image"})
package com.mymedia.web;
```

- [ ] **Step 4: 写 DTO 与控制器**

`src/main/java/com/mymedia/web/GlobalSearchDto.java`：

```java
package com.mymedia.web;

import com.mymedia.image.ImageSearchHit;
import com.mymedia.video.VideoSearchHit;

import java.util.List;

final class GlobalSearchDto {

    private GlobalSearchDto() {
    }

    /**
     * 两个域各一个数组。
     *
     * <p>没有命中的那一边返回<b>空数组而不是省略字段</b>——前端的两个分区是常驻的，
     * 缺字段只会让它多写一堆判空。
     */
    record Response(String query, List<VideoSearchHit> video, List<ImageSearchHit> image) {
    }
}
```

`src/main/java/com/mymedia/web/GlobalSearchController.java`：

```java
package com.mymedia.web;

import com.mymedia.image.ImageSearchService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoSearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索：一次输入，两个域各查各的，分区返回。
 *
 * <p>两次查询是顺序发出的，没有并行化——它们各自都是一条走索引的 SQL，
 * 加起来通常在几十毫秒内。为省这点时间引入线程池、再引入线程池的配置与
 * 关闭逻辑，是典型的用复杂度换不需要的性能。
 */
@RestController
class GlobalSearchController {

    private static final int MAX_LIMIT = 100;

    private final VideoSearchService videoSearch;
    private final ImageSearchService imageSearch;
    private final UserQueryService userQueryService;

    GlobalSearchController(VideoSearchService videoSearch,
                           ImageSearchService imageSearch,
                           UserQueryService userQueryService) {
        this.videoSearch = videoSearch;
        this.imageSearch = imageSearch;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/search")
    GlobalSearchDto.Response search(@AuthenticationPrincipal UserDetails principal,
                                    @RequestParam("q") String q,
                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        SearchQuery query = SearchQuery.of(q);
        int capped = Math.clamp(limit, 1, MAX_LIMIT);

        return new GlobalSearchDto.Response(
                query.normalized(),
                videoSearch.search(userId, query, capped),
                imageSearch.search(userId, query, capped));
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='GlobalSearchControllerTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/web src/test/java/com/mymedia/web
git commit -m "feat: 创建 web 模块与全局搜索端点

web 模块是 spec 4.2 早就声明、一直没用到的那个；全局搜索是本项目
第一个真正需要同时看见两个域的东西，也是唯一可以同时依赖它们的地方——
塞进任何一个领域模块都会让其中一个依赖另一个。

结果分区返回不混排：两个域的卡片布局与可用操作都不一样，
混排之后前端第一件事就是把它们再拆开。"
```

Expected: `EXIT=0`，`GlobalSearchControllerTest` 5 个用例通过，`ModularityTests` 承认新模块。

---

## Task 5: 标签模型与管理

**Files:**
- Create: `src/main/resources/db/migration/V13__tags.sql`
- Create: `src/main/java/com/mymedia/metadata/Tag.java`
- Create: `src/main/java/com/mymedia/metadata/TagRepository.java`
- Create: `src/main/java/com/mymedia/metadata/TagSlug.java`
- Create: `src/main/java/com/mymedia/metadata/TagService.java`
- Create: `src/main/java/com/mymedia/metadata/web/TagDto.java`
- Create: `src/main/java/com/mymedia/metadata/web/TagController.java`
- Test: `src/test/java/com/mymedia/metadata/TagSlugTest.java`
- Test: `src/test/java/com/mymedia/metadata/TagServiceTest.java`

**Interfaces:**
- Consumes: `LibraryDomain`（计划 01）、`NotFoundException`（计划 01）
- Produces:
  - `public class Tag` — getter：`Long getId()`、`LibraryDomain getDomain()`、`String getName()`、`String getSlug()`
  - `class TagSlug`（package-private）— `static String of(String name)`
  - `public class TagService`
    - `public Tag findOrCreate(LibraryDomain domain, String name)`
    - `public List<Tag> findByDomain(LibraryDomain domain)`
    - `public Tag getById(Long tagId)`
    - `public void delete(Long tagId)`
  - `GET /api/tags?domain=`、`POST /api/tags`（ADMIN）、`DELETE /api/tags/{id}`（ADMIN）

### 标签归 `metadata` 模块

标签就是内容元数据，而 `metadata` 已经同时持有 `video` 与 `image` 两条依赖边
（计划 05 为刮削建立的）。放这里**不需要任何新的模块间依赖**，
关联表也和 `scrape_candidate` 一样是「metadata 自己的表，只是外键指向领域表」——
这个先例计划 05 已经立过了。

### 域分区在标签上同样是数据库强制的

一个 `VIDEO` 标签绝不能贴到图片节点上。这里**复用 ADR-001 的复合外键手法**：
给 `tag` 加一个冗余唯一键 `(id, domain)`，关联表冗余一列 `domain` 并用
`CHECK` 钉死取值 + 复合外键指回去。

```
video_item_tag.domain 恒为 'VIDEO'  ──复合外键──>  tag(id, domain)
```

效果：把一个 IMAGE 标签插进 `video_item_tag` 在**数据库层面就不可能**。
同一个手法在本项目里第三次出现（`video_item`、`collection`、现在是标签关联表），
它是这套设计的招牌。

### slug 对中文保留原字

`TagSlug` 做的是小写化、空白折叠成连字符、去掉标点，**不做音译**。
中文标签的 slug 就是中文本身——音译需要一张词表或一个外部库，
而 slug 在本项目里的用途只是「给 `(domain, slug)` 做唯一键，
让『科幻』和『 科幻 』被认成同一个标签」，不需要它可读成 ASCII。

- [ ] **Step 1: 写会失败的 slug 单元测试**

`src/test/java/com/mymedia/metadata/TagSlugTest.java`：

```java
package com.mymedia.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagSlugTest {

    @Test
    void lowercasesAndHyphenatesLatin() {
        assertThat(TagSlug.of("Sci Fi")).isEqualTo("sci-fi");
    }

    @Test
    void keepsChineseCharactersAsTheyAre() {
        // 音译需要词表或外部库，而 slug 在这里只用来做唯一键
        assertThat(TagSlug.of("科幻")).isEqualTo("科幻");
    }

    @Test
    void collapsesRepeatedWhitespaceAndTrims() {
        assertThat(TagSlug.of("  科幻   动作  ")).isEqualTo("科幻-动作");
    }

    @Test
    void dropsPunctuationSoNearlyIdenticalNamesCollide() {
        // 「科幻！」与「科幻」应当是同一个标签
        assertThat(TagSlug.of("科幻！")).isEqualTo("科幻");
        assertThat(TagSlug.of("Sci-Fi!")).isEqualTo("sci-fi");
    }

    @Test
    void keepsExistingHyphensWithoutDoublingThem() {
        assertThat(TagSlug.of("sci - fi")).isEqualTo("sci-fi");
        assertThat(TagSlug.of("sci--fi")).isEqualTo("sci-fi");
    }

    @Test
    void trimsLeadingAndTrailingHyphens() {
        assertThat(TagSlug.of("-科幻-")).isEqualTo("科幻");
    }

    @Test
    void keepsDigits() {
        assertThat(TagSlug.of("2024 年度")).isEqualTo("2024-年度");
    }

    @Test
    void rejectsNamesThatSlugifyToNothing() {
        // 全是标点的名字没法做唯一键，必须当场拒绝而不是存一个空 slug
        assertThatThrownBy(() -> TagSlug.of("！！！"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagSlug.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 写会失败的服务测试**

`src/test/java/com/mymedia/metadata/TagServiceTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagServiceTest extends AbstractIntegrationTest {

    @Autowired
    TagService tagService;

    @Autowired
    JdbcTemplate jdbc;

    private String uniqueName() {
        return "标签" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsATagWithAGeneratedSlug() {
        Tag tag = tagService.findOrCreate(LibraryDomain.VIDEO, "科  幻");

        assertThat(tag.getId()).isNotNull();
        assertThat(tag.getName()).isEqualTo("科  幻");
        assertThat(tag.getSlug()).isEqualTo("科-幻");
        assertThat(tag.getDomain()).isEqualTo(LibraryDomain.VIDEO);
    }

    @Test
    void namesThatDifferOnlyByPunctuationOrCaseAreTheSameTag() {
        Tag first = tagService.findOrCreate(LibraryDomain.VIDEO, "Sci-Fi");
        Tag second = tagService.findOrCreate(LibraryDomain.VIDEO, "sci fi!");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void theSameSlugInTheOtherDomainIsADifferentTag() {
        // 视频标签与图片标签互不混用（spec §6.2）
        String name = uniqueName();
        Tag videoTag = tagService.findOrCreate(LibraryDomain.VIDEO, name);
        Tag imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, name);

        assertThat(imageTag.getId()).isNotEqualTo(videoTag.getId());
    }

    @Test
    void listsOnlyTheRequestedDomain() {
        String name = uniqueName();
        tagService.findOrCreate(LibraryDomain.VIDEO, name);

        assertThat(tagService.findByDomain(LibraryDomain.VIDEO))
                .extracting(Tag::getName).contains(name);
        assertThat(tagService.findByDomain(LibraryDomain.IMAGE))
                .extracting(Tag::getName).doesNotContain(name);
    }

    @Test
    void deletesATag() {
        Tag tag = tagService.findOrCreate(LibraryDomain.VIDEO, uniqueName());

        tagService.delete(tag.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tag WHERE id = ?",
                Integer.class, tag.getId())).isZero();
    }

    @Test
    void theDatabaseRefusesAnImageTagOnAVideoItem() {
        // ADR-001 的复合外键手法在标签上的第三次应用
        Tag imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, uniqueName());
        Long libraryId = jdbc.queryForObject("""
                INSERT INTO libraries (name, domain, root_path)
                VALUES ('库' || gen_random_uuid(), 'VIDEO', '/tmp/' || gen_random_uuid())
                RETURNING id
                """, Long.class);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '某片', '某片') RETURNING id
                """, Long.class, libraryId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO video_item_tag (video_item_id, tag_id) VALUES (?, ?)",
                itemId, imageTag.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownTagIdIsNotFound() {
        assertThatThrownBy(() -> tagService.getById(999_999_999L))
                .isInstanceOf(com.mymedia.shared.NotFoundException.class);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='TagSlugTest,TagServiceTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`TagSlug`、`TagService` 不存在。

- [ ] **Step 4: 写迁移脚本**

`src/main/resources/db/migration/V13__tags.sql`：

```sql
-- ============================================================
-- 标签。视频标签与图片标签互不混用（spec §6.2），
-- 而这条不变式和 video_item / collection 一样由数据库强制，
-- 用的是同一个复合外键手法，见 ADR-001。
-- ============================================================

CREATE TABLE tag (
    id         BIGSERIAL PRIMARY KEY,
    domain     VARCHAR(8)  NOT NULL,
    name       TEXT        NOT NULL,
    -- 小写化、空白折叠、去标点后的形式，只用来做唯一键；中文保留原字
    slug       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tag_domain CHECK (domain IN ('VIDEO', 'IMAGE'))
);

-- 「科幻」与「科幻！」是同一个标签；视频的「科幻」与图片的「科幻」不是
ALTER TABLE tag ADD CONSTRAINT uq_tag_domain_slug UNIQUE (domain, slug);

-- 看似冗余的唯一键，是让关联表能用复合外键把 domain 钉死的前提
ALTER TABLE tag ADD CONSTRAINT uq_tag_id_domain UNIQUE (id, domain);

CREATE INDEX idx_tag_domain_name ON tag (domain, name);

CREATE TABLE video_item_tag (
    video_item_id BIGINT     NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    tag_id        BIGINT     NOT NULL,
    domain        VARCHAR(8) NOT NULL DEFAULT 'VIDEO',
    PRIMARY KEY (video_item_id, tag_id),
    CONSTRAINT ck_video_item_tag_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_video_item_tag_domain
        FOREIGN KEY (tag_id, domain) REFERENCES tag (id, domain) ON DELETE CASCADE
);

-- 按标签列条目走这个索引（主键是 (item, tag)，反向查需要它）
CREATE INDEX idx_video_item_tag_tag ON video_item_tag (tag_id);

CREATE TABLE image_node_tag (
    image_node_id BIGINT     NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    tag_id        BIGINT     NOT NULL,
    domain        VARCHAR(8) NOT NULL DEFAULT 'IMAGE',
    PRIMARY KEY (image_node_id, tag_id),
    CONSTRAINT ck_image_node_tag_is_image CHECK (domain = 'IMAGE'),
    CONSTRAINT fk_image_node_tag_domain
        FOREIGN KEY (tag_id, domain) REFERENCES tag (id, domain) ON DELETE CASCADE
);

CREATE INDEX idx_image_node_tag_tag ON image_node_tag (tag_id);
```

- [ ] **Step 5: 写 slug 生成**

`src/main/java/com/mymedia/metadata/TagSlug.java`：

```java
package com.mymedia.metadata;

import java.util.Locale;

/**
 * 标签名 → slug。
 *
 * <p>slug 在本项目里的唯一用途是给 {@code (domain, slug)} 做唯一键，
 * 让「科幻」「科幻！」「 科幻 」被认成同一个标签。
 * <b>不做音译</b>——那需要一张词表或一个外部库，而这里根本不需要 slug 可读成 ASCII。
 *
 * <p>纯逻辑，无依赖。
 */
final class TagSlug {

    private TagSlug() {
    }

    static String of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        StringBuilder builder = new StringBuilder(name.length());
        name.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint);
            } else {
                // 空白、标点、已有的连字符统统折叠成一个分隔符
                builder.append('-');
            }
        });

        String slug = builder.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            // 全是标点的名字做不了唯一键，当场拒绝而不是存一个空 slug
            throw new IllegalArgumentException("标签名里没有可用字符: " + name);
        }
        return slug;
    }
}
```

- [ ] **Step 6: 写实体、仓储与服务**

`src/main/java/com/mymedia/metadata/Tag.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
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
@Table(name = "tag")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private LibraryDomain domain;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Tag() {
        // JPA 要求的无参构造器
    }

    Tag(LibraryDomain domain, String name, String slug) {
        this.domain = domain;
        this.name = name;
        this.slug = slug;
    }

    public Long getId() { return id; }
    public LibraryDomain getDomain() { return domain; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`src/main/java/com/mymedia/metadata/TagRepository.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByDomainAndSlug(LibraryDomain domain, String slug);

    List<Tag> findByDomainOrderByName(LibraryDomain domain);
}
```

`src/main/java/com/mymedia/metadata/TagService.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标签的增删查。
 *
 * <p>标签归 {@code metadata} 模块：它就是内容元数据，而本模块已经同时持有
 * {@code video} 与 {@code image} 两条依赖边（计划 05 为刮削建立的），
 * 放这里不需要任何新的模块间依赖。关联表和 {@code scrape_candidate} 一样，
 * 是「本模块自己的表，只是外键指向领域表」。
 */
@Service
public class TagService {

    private final TagRepository repository;

    TagService(TagRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 (域, slug) 找或建。
     *
     * <p>用 find-or-create 而不是 create：标签是打的时候顺手建的，
     * 让调用方先查一次再建只会到处重复这段逻辑。
     */
    @Transactional
    public Tag findOrCreate(LibraryDomain domain, String name) {
        String slug = TagSlug.of(name);
        return repository.findByDomainAndSlug(domain, slug)
                .orElseGet(() -> repository.saveAndFlush(new Tag(domain, name.trim(), slug)));
    }

    @Transactional(readOnly = true)
    public List<Tag> findByDomain(LibraryDomain domain) {
        return repository.findByDomainOrderByName(domain);
    }

    @Transactional(readOnly = true)
    public Tag getById(Long tagId) {
        return repository.findById(tagId)
                .orElseThrow(() -> new NotFoundException("找不到标签 id=" + tagId));
    }

    /** 关联表上的外键是 ON DELETE CASCADE，删标签会一并解掉所有关联。 */
    @Transactional
    public void delete(Long tagId) {
        repository.delete(getById(tagId));
    }
}
```

- [ ] **Step 7: 写端点**

`src/main/java/com/mymedia/metadata/web/TagDto.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class TagDto {

    private TagDto() {
    }

    record CreateRequest(@NotNull LibraryDomain domain,
                         @NotBlank @Size(max = 64) String name) {
    }

    record Response(Long id, LibraryDomain domain, String name, String slug) {

        static Response from(Tag tag) {
            return new Response(tag.getId(), tag.getDomain(), tag.getName(), tag.getSlug());
        }
    }
}
```

`src/main/java/com/mymedia/metadata/web/TagController.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.TagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签本身的管理。
 *
 * <p>标签是**全库共享的词表**，不属于任何一个媒体库，因此建与删限 ADMIN；
 * 列出对所有登录用户开放（不列出会让打标签的下拉框没法填）。
 */
@RestController
@RequestMapping("/api/tags")
class TagController {

    private final TagService tagService;

    TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    List<TagDto.Response> list(@RequestParam LibraryDomain domain) {
        return tagService.findByDomain(domain).stream().map(TagDto.Response::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    TagDto.Response create(@Valid @RequestBody TagDto.CreateRequest request) {
        // TagSlug.of 对全标点的名字抛 IllegalArgumentException → GlobalExceptionHandler 翻成 400
        return TagDto.Response.from(tagService.findOrCreate(request.domain(), request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable Long id) {
        tagService.delete(id);
    }
}
```

- [ ] **Step 8: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='TagSlugTest,TagServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V13__tags.sql src/main/java/com/mymedia/metadata src/test/java/com/mymedia/metadata
git commit -m "feat: 添加标签模型与管理端点

标签归 metadata 模块：它就是内容元数据，且该模块已有 video/image 两条边，
放这里不新增任何模块间依赖，关联表与 scrape_candidate 是同一个先例。

域分区在标签上同样由数据库强制——复用 ADR-001 的复合外键手法，
把 IMAGE 标签贴到视频条目上在数据库层面就不可能。这是该手法第三次出现。

slug 对中文保留原字：它只用来做唯一键，不需要可读成 ASCII。"
```

Expected: `EXIT=0`，`TagSlugTest` 8 个、`TagServiceTest` 7 个用例通过。

---

## Task 6: 打标签与按标签浏览

**Files:**
- Create: `src/main/java/com/mymedia/metadata/TagLinkStore.java`
- Modify: `src/main/java/com/mymedia/metadata/TagService.java`（新增 3 个方法 + 1 个依赖）
- Create: `src/main/java/com/mymedia/metadata/web/TagLinkController.java`
- Modify: `src/main/java/com/mymedia/metadata/web/TagDto.java`（新增两个 record）
- Modify: `src/main/java/com/mymedia/video/VideoItem.java`（补只读的 `coverAssetId` 映射）
- Modify: `src/main/java/com/mymedia/video/VideoCatalogService.java`（新增 `findByIds`）
- Modify: `src/main/java/com/mymedia/image/ImageCatalogService.java`（新增 `findByIds`）
- Test: `src/test/java/com/mymedia/metadata/TagLinkTest.java`

**Interfaces:**
- Consumes: `TagService`、`Tag`（Task 5）、`VideoCatalogService`、`ImageCatalogService`（计划 03/04）、`LibraryAccessService`、`UserQueryService`（计划 01）
- Produces:
  - `TagService` 新增：
    - `public List<Tag> tagsOf(LibraryDomain domain, Long targetId)`
    - `public List<Tag> setTags(LibraryDomain domain, Long targetId, List<Long> tagIds)`
    - `public List<Long> targetIdsWithTag(Long tagId, int limit)`
  - `VideoItem` 新增：`public Long getCoverAssetId()`
  - `VideoCatalogService.findByIds(Collection<Long> itemIds)` → `List<VideoItem>`（保持入参顺序）
  - `ImageCatalogService.findByIds(Collection<Long> nodeIds)` → `List<ImageNode>`（保持入参顺序）
  - `GET|PUT /api/video/items/{id}/tags`、`GET|PUT /api/image/nodes/{id}/tags`、`GET /api/tags/{id}/items`

### `setTags` 是整体替换，不是增量

前端的标签编辑器是一个多选框：用户勾完点保存，提交的是**最终应该有的那一组**。
做成 `addTag` / `removeTag` 两个端点，前端就得自己算差集，还得处理两次请求之间失败的中间态。
一次 `PUT` 覆盖整组，语义与界面一致，也天然幂等。

### 按标签列条目为什么要绕一圈领域 API

`video_item_tag` 是 `metadata` 自己的表，直接查没问题；但**条目的标题与封面在 `video_item` 里**，
那是 `video` 的表。所以流程是：本模块查出 id 列表 → 调 `VideoCatalogService.findByIds` 取实体 →
按访问权过滤。这与计划 05 里「`preview` 不直接 SELECT `video_item`」是同一条纪律。

`findByIds` 要**保持入参顺序**：id 列表是按标签关联的插入顺序取的，
`WHERE id IN (…)` 返回的顺序由数据库决定，不重排就会让列表每次刷新都换个样子。

### `VideoItem.coverAssetId` 映射成只读，不是疏忽

`video_item.cover_asset_id` 这一列在计划 03 的 V6 里就有，但计划 03 **没有映射它**，
计划 05 也刻意没加——它在 `VideoCatalogService.assignCoverIfAbsent` 里由一条
`UPDATE … WHERE cover_asset_id IS NULL` 原子写入，那条 SQL 同时表达了「判断没有封面」
与「写入封面」，加 JPA setter 反而会把原子性破坏掉。

本任务要**读**它（标签卡片要显示封面），所以补一个 `insertable = false, updatable = false`
的映射：类型系统上就写明「这一列由别人维护，本实体只读」。
若映射成可写，一个在封面写入**之前**加载、在写入**之后**刷新的 `VideoItem`
会把 Hibernate 缓存里的 `null` 刷回去，悄悄抹掉一张刚生成好的封面。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/metadata/TagLinkTest.java`：

```java
package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TagLinkTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TagService tagService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long itemId;
    private String username;

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        itemId = insertItem(library.getId(), "沙漠风暴");

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());
    }

    private Long tagId(String name) {
        return tagService.findOrCreate(LibraryDomain.VIDEO, name).getId();
    }

    @Test
    void setTagsReplacesTheWholeSetRatherThanAppending() {
        Long action = tagId("动作" + UUID.randomUUID());
        Long war = tagId("战争" + UUID.randomUUID());
        Long drama = tagId("剧情" + UUID.randomUUID());

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action, war));
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(drama));

        assertThat(tagService.tagsOf(LibraryDomain.VIDEO, itemId))
                .extracting(Tag::getId)
                .containsExactly(drama);
    }

    @Test
    void settingTheSameTagTwiceIsIdempotent() {
        Long action = tagId("动作" + UUID.randomUUID());

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action, action));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE video_item_id = ?",
                Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void anEmptyListClearsAllTags() {
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(tagId("动作" + UUID.randomUUID())));

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of());

        assertThat(tagService.tagsOf(LibraryDomain.VIDEO, itemId)).isEmpty();
    }

    @Test
    void listsTargetsCarryingATag() {
        Long action = tagId("动作" + UUID.randomUUID());
        Long secondItem = insertItem(library.getId(), "雪原突击");
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));
        tagService.setTags(LibraryDomain.VIDEO, secondItem, List.of(action));

        assertThat(tagService.targetIdsWithTag(action, 20))
                .containsExactlyInAnyOrder(itemId, secondItem);
    }

    @Test
    void deletingATagUnlinksItEverywhere() {
        Long action = tagId("动作" + UUID.randomUUID());
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        tagService.delete(action);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE video_item_id = ?",
                Integer.class, itemId)).isZero();
    }

    @Test
    void deletingAnItemUnlinksItsTags() {
        Long action = tagId("动作" + UUID.randomUUID());
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE tag_id = ?",
                Integer.class, action)).isZero();
    }

    @Test
    void endpointReplacesTagsAndReadsThemBack() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());

        mockMvc.perform(put("/api/video/items/{id}/tags", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + action + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));

        mockMvc.perform(get("/api/video/items/{id}/tags", itemId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(action));
    }

    @Test
    void tagBrowseEndpointHidesTargetsTheUserCannotAccess() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));
        tagService.setTags(LibraryDomain.VIDEO, hidden, List.of(action));

        mockMvc.perform(get("/api/tags/{id}/items", action).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("沙漠风暴"));
    }

    @Test
    void taggingAnItemInAnInaccessibleLibraryIsNotFound() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(put("/api/video/items/{id}/tags", hidden)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + action + "]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTagFromTheOtherDomainIsRejectedBeforeReachingTheDatabase() throws Exception {
        Long imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, "画集" + UUID.randomUUID()).getId();

        // 数据库那道复合外键是最后一道防线；服务层应当先给出一个能读懂的错误
        mockMvc.perform(put("/api/video/items/{id}/tags", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + imageTag + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taggedTargetCarriesTheCoverSoTheCardCanBeDrawn() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        // derived_asset 由计划 05 的 V10 建表；这里只需要一行能被外键接受的记录
        Long assetId = jdbc.queryForObject("""
                INSERT INTO derived_asset (kind, source_scanned_file_id, relative_path, size_bytes)
                VALUES ('COVER', NULL, 'covers/test-' || gen_random_uuid() || '.jpg', 1024)
                RETURNING id
                """, Long.class);
        jdbc.update("UPDATE video_item SET cover_asset_id = ? WHERE id = ?", assetId, itemId);
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        mockMvc.perform(get("/api/tags/{id}/items", action).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].coverAssetId").value(assetId));
    }
}
```

> 最后一个用例里 `source_scanned_file_id` 传 `NULL`：计划 05 的 `derived_asset` 表上
> 这一列是可空外键（派生资源可以不来自某个具体文件）。若执行计划 05 时把它改成了
> `NOT NULL`，这里就先插一行 `scanned_file` 再引用它。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=TagLinkTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`TagService.setTags` 不存在。

- [ ] **Step 3: 写关联表读写**

`src/main/java/com/mymedia/metadata/TagLinkStore.java`：

```java
package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code video_item_tag} / {@code image_node_tag} 的读写。
 *
 * <p>两张表是本模块自己的（和 {@code scrape_candidate} 一样，只是外键指向领域表），
 * 所以直接查是本模块的事，不算跨模块 SQL。但<b>条目的标题与封面在领域表里</b>，
 * 那个要绕 {@code VideoCatalogService.findByIds} 走。
 */
@Component
class TagLinkStore {

    private final JdbcTemplate jdbc;

    TagLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Long> tagIdsOf(LibraryDomain domain, Long targetId) {
        return jdbc.queryForList("SELECT tag_id FROM " + table(domain)
                + " WHERE " + targetColumn(domain) + " = ? ORDER BY tag_id", Long.class, targetId);
    }

    /** 整体替换：先清空再写入。调用方保证 tagIds 已去重且都属于 domain。 */
    void replace(LibraryDomain domain, Long targetId, List<Long> tagIds) {
        jdbc.update("DELETE FROM " + table(domain) + " WHERE " + targetColumn(domain) + " = ?",
                targetId);
        for (Long tagId : tagIds) {
            jdbc.update("INSERT INTO " + table(domain)
                    + " (" + targetColumn(domain) + ", tag_id) VALUES (?, ?)", targetId, tagId);
        }
    }

    List<Long> targetIdsWithTag(LibraryDomain domain, Long tagId, int limit) {
        return jdbc.queryForList("SELECT " + targetColumn(domain) + " FROM " + table(domain)
                        + " WHERE tag_id = ? ORDER BY " + targetColumn(domain) + " LIMIT ?",
                Long.class, tagId, limit);
    }

    /** 表名与列名由枚举决定，不是外部输入，拼进 SQL 是安全的。 */
    private static String table(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_tag" : "image_node_tag";
    }

    private static String targetColumn(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_id" : "image_node_id";
    }
}
```

- [ ] **Step 4: 扩展 `TagService`**

在 `src/main/java/com/mymedia/metadata/TagService.java` 中新增构造参数 `TagLinkStore linkStore`
（连同同名字段一起加，Task 5 已有的 `repository` 保持不变），并追加三个方法：

```java
    @Transactional(readOnly = true)
    public List<Tag> tagsOf(LibraryDomain domain, Long targetId) {
        List<Long> tagIds = linkStore.tagIdsOf(domain, targetId);
        return tagIds.isEmpty() ? List.of() : repository.findAllById(tagIds);
    }

    /**
     * 整体替换某个目标的标签组。
     *
     * <p><b>替换而不是增删</b>：前端的标签编辑器是一个多选框，用户勾完点保存，
     * 提交的就是「最终应该有的那一组」。做成 add/remove 两个端点会逼前端自己算差集，
     * 还要处理两次请求之间失败的中间态。一次覆盖，语义与界面一致，天然幂等。
     *
     * @throws IllegalArgumentException 有标签不存在，或有标签不属于该域。数据库那道
     *         复合外键是最后一道防线，但它给出的错误是 FK 违例，调用方读不懂；
     *         这里先给一个能读懂的。
     */
    @Transactional
    public List<Tag> setTags(LibraryDomain domain, Long targetId, List<Long> tagIds) {
        List<Long> distinct = tagIds.stream().distinct().toList();
        List<Tag> tags = distinct.isEmpty() ? List.of() : repository.findAllById(distinct);

        if (tags.size() != distinct.size()) {
            throw new IllegalArgumentException("有标签不存在: " + distinct);
        }
        tags.stream()
                .filter(tag -> tag.getDomain() != domain)
                .findFirst()
                .ifPresent(tag -> {
                    throw new IllegalArgumentException(
                            "标签 " + tag.getName() + " 属于 " + tag.getDomain() + " 域，不能贴到 " + domain);
                });

        linkStore.replace(domain, targetId, distinct);
        return tags;
    }

    /** 标签自己带 domain，所以调用方不需要再传一次。 */
    @Transactional(readOnly = true)
    public List<Long> targetIdsWithTag(Long tagId, int limit) {
        Tag tag = getById(tagId);
        return linkStore.targetIdsWithTag(tag.getDomain(), tagId, limit);
    }
```

- [ ] **Step 5: 给 `VideoItem` 补只读的封面映射**

在 `src/main/java/com/mymedia/video/VideoItem.java` 的字段区追加（放在 `summary` 之后、
`createdAt` 之前）：

```java
    /**
     * 封面派生资源 id。
     *
     * <p><b>只读映射</b>：这一列由计划 05 的
     * {@code VideoCatalogService.assignCoverIfAbsent} 用一条
     * {@code UPDATE … WHERE cover_asset_id IS NULL} 原子写入，那条 SQL 同时表达了
     * 「判断没有封面」与「写入封面」。若这里映射成可写，一个在写入之前加载、
     * 在写入之后刷新的实体会把缓存里的 {@code null} 刷回去，悄悄抹掉一张刚生成好的封面。
     */
    @Column(name = "cover_asset_id", insertable = false, updatable = false)
    private Long coverAssetId;
```

并在 getter 区追加：

```java
    public Long getCoverAssetId() { return coverAssetId; }
```

- [ ] **Step 6: 给两个领域模块加批量取实体**

在 `src/main/java/com/mymedia/video/VideoCatalogService.java` 追加：

```java
    /**
     * 按 id 批量取条目，<b>保持入参顺序</b>。
     *
     * <p>顺序很重要：调用方（按标签浏览、收藏列表）的 id 是有序取出来的，
     * 而 {@code WHERE id IN (…)} 的返回顺序由数据库决定。不重排就会让同一个列表
     * 每次刷新都换个样子。
     *
     * <p>查不到的 id 被静默丢弃而不是留一个 {@code null}：条目可能在调用方
     * 取到 id 之后被删掉，让列表短一项比让它带一个洞好。
     */
    @Transactional(readOnly = true)
    public List<VideoItem> findByIds(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        Map<Long, VideoItem> byId = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(VideoItem::getId, item -> item));
        return itemIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
```

需要新增 import：`java.util.Collection`、`java.util.Map`、`java.util.Objects`、`java.util.stream.Collectors`。

在 `src/main/java/com/mymedia/image/ImageCatalogService.java` 追加：

```java
    /** 按 id 批量取节点，<b>保持入参顺序</b>。理由同 {@code VideoCatalogService.findByIds}。 */
    @Transactional(readOnly = true)
    public List<ImageNode> findByIds(Collection<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ImageNode> byId = nodeRepository.findAllById(nodeIds).stream()
                .collect(Collectors.toMap(ImageNode::getId, node -> node));
        return nodeIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
```

同样需要新增那四个 import。

- [ ] **Step 7: 写端点**

在 `src/main/java/com/mymedia/metadata/web/TagDto.java` 追加两个 record
（需要 import `jakarta.validation.constraints.NotNull` 与 `java.util.List`）：

```java
    record SetTagsRequest(@NotNull List<Long> tagIds) {
    }

    /** 按标签浏览的结果。两个域共用一个形状——它只需要够画一张卡片。 */
    record TaggedTarget(Long id, String title, Long coverAssetId) {
    }
```

`src/main/java/com/mymedia/metadata/web/TagLinkController.java`：

```java
package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.Tag;
import com.mymedia.metadata.TagService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 给条目打标签，以及按标签浏览。
 *
 * <p>URL 按领域切分（{@code /api/video/items/…}、{@code /api/image/nodes/…}），
 * 实现住在 {@code metadata}——与计划 05 的元数据编辑端点同一个理由：
 * 接口按领域切分是对外部 URL 的要求，不要求实现类住在哪个模块。
 */
@RestController
class TagLinkController {

    private static final int MAX_LIMIT = 200;

    private final TagService tagService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    TagLinkController(TagService tagService,
                      VideoCatalogService videoCatalog,
                      ImageCatalogService imageCatalog,
                      LibraryAccessService accessService,
                      UserQueryService userQueryService) {
        this.tagService = tagService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/video/items/{id}/tags")
    List<TagDto.Response> videoTags(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        requireVideoAccess(principal, id);
        return tagService.tagsOf(LibraryDomain.VIDEO, id).stream()
                .map(TagDto.Response::from).toList();
    }

    @PutMapping("/api/video/items/{id}/tags")
    List<TagDto.Response> setVideoTags(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody TagDto.SetTagsRequest request) {
        requireVideoAccess(principal, id);
        return tagService.setTags(LibraryDomain.VIDEO, id, request.tagIds()).stream()
                .map(TagDto.Response::from).toList();
    }

    @GetMapping("/api/image/nodes/{id}/tags")
    List<TagDto.Response> imageTags(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        requireImageAccess(principal, id);
        return tagService.tagsOf(LibraryDomain.IMAGE, id).stream()
                .map(TagDto.Response::from).toList();
    }

    @PutMapping("/api/image/nodes/{id}/tags")
    List<TagDto.Response> setImageTags(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody TagDto.SetTagsRequest request) {
        requireImageAccess(principal, id);
        return tagService.setTags(LibraryDomain.IMAGE, id, request.tagIds()).stream()
                .map(TagDto.Response::from).toList();
    }

    /** 按标签列条目。标签自己带 domain，所以不需要调用方再传一次。 */
    @GetMapping("/api/tags/{id}/items")
    List<TagDto.TaggedTarget> targets(@AuthenticationPrincipal UserDetails principal,
                                      @PathVariable Long id,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Long userId = currentUserId(principal);
        Tag tag = tagService.getById(id);
        List<Long> targetIds = tagService.targetIdsWithTag(id, Math.clamp(limit, 1, MAX_LIMIT));

        if (tag.getDomain() == LibraryDomain.VIDEO) {
            return videoCatalog.findByIds(targetIds).stream()
                    .filter(item -> accessService.canAccess(userId, item.getLibraryId()))
                    .map(item -> new TagDto.TaggedTarget(
                            item.getId(), item.getTitle(), item.getCoverAssetId()))
                    .toList();
        }
        return imageCatalog.findByIds(targetIds).stream()
                .filter(node -> accessService.canAccess(userId, node.getLibraryId()))
                .map(node -> new TagDto.TaggedTarget(
                        node.getId(), node.getDisplayName(), node.getCoverAssetId()))
                .toList();
    }

    private void requireVideoAccess(UserDetails principal, Long itemId) {
        VideoItem item = videoCatalog.getItem(itemId);
        if (!accessService.canAccess(currentUserId(principal), item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
    }

    private void requireImageAccess(UserDetails principal, Long nodeId) {
        ImageNode node = imageCatalog.getNode(nodeId);
        if (!accessService.canAccess(currentUserId(principal), node.getLibraryId())) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

- [ ] **Step 8: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='TagLinkTest,TagServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia/metadata/TagLinkTest.java
git commit -m "feat: 添加打标签与按标签浏览

setTags 是整体替换而非增删：前端的标签编辑器提交的就是'最终应该有的那一组'，
做成 add/remove 会逼前端自己算差集，还要处理中间态。

按标签列条目绕 VideoCatalogService.findByIds 走，不直接 SELECT 领域表——
与计划 05 的同一条纪律。findByIds 保持入参顺序，否则列表每次刷新都换个样子。

VideoItem.coverAssetId 映射成只读：这一列由 assignCoverIfAbsent 的
原子 UPDATE 写入，可写映射会让陈旧实体把刚生成的封面刷成 null。

跨域标签在服务层先给一个能读懂的错误，数据库的复合外键是最后一道防线。"
```

Expected: `EXIT=0`，`TagLinkTest` 11 个用例通过。

---

## Task 7: 两个域的收藏

**Files:**
- Create: `src/main/resources/db/migration/V14__favorites.sql`
- Create: `src/main/java/com/mymedia/video/VideoFavorite.java`
- Create: `src/main/java/com/mymedia/video/VideoFavoriteRepository.java`
- Create: `src/main/java/com/mymedia/video/VideoFavoriteService.java`
- Create: `src/main/java/com/mymedia/video/web/VideoFavoriteController.java`
- Create: `src/main/java/com/mymedia/image/ImageFavorite.java`
- Create: `src/main/java/com/mymedia/image/ImageFavoriteRepository.java`
- Create: `src/main/java/com/mymedia/image/ImageFavoriteService.java`
- Create: `src/main/java/com/mymedia/image/web/ImageFavoriteController.java`
- Test: `src/test/java/com/mymedia/video/VideoFavoriteServiceTest.java`
- Test: `src/test/java/com/mymedia/image/ImageFavoriteServiceTest.java`

**Interfaces:**
- Consumes: `VideoCatalogService.findByIds` / `ImageCatalogService.findByIds`（Task 6）、`LibraryAccessService`、`UserQueryService`（计划 01）
- Produces:
  - `public class VideoFavoriteService`
    - `public void add(Long userId, Long itemId)`
    - `public void remove(Long userId, Long itemId)`
    - `public boolean isFavorite(Long userId, Long itemId)`
    - `public List<VideoItem> listItems(Long userId, int limit)`
  - `public class ImageFavoriteService`
    - `public void add(Long userId, Long nodeId)`
    - `public void remove(Long userId, Long nodeId)`
    - `public boolean isFavorite(Long userId, Long nodeId)`
    - `public List<ImageNode> listNodes(Long userId, int limit)`
  - `PUT|DELETE /api/video/items/{id}/favorite`、`GET /api/video/favorites`
  - `PUT|DELETE /api/image/nodes/{id}/favorite`、`GET /api/image/favorites`

### 两个域一起做，因为它们是同一段代码写两遍

收藏是纯粹的用户态：一张复合主键表、四个方法、四个端点，两个域除了目标列名之外
一模一样。拆成两个任务，评审时也只会一起看。**但它们仍然住在各自的领域模块里**——
和播放进度、阅读进度一样（spec §6.5 把它们归在一起），
把用户态抽到一个公共模块会立刻需要一个「目标类型」的多态列，那正是本项目一直在避免的东西。

下面两个域的代码**逐字写全**，不写「照抄上面改个名」：执行者可能只拿到本任务的一半，
而两段代码之间的差异恰恰是最容易抄错的地方（`listItems` vs `listNodes`、
`getItem` vs `getNode`）。

### 图片域可以收藏文件夹

`image_favorite` 允许收藏**任意节点，包括纯目录**（spec §6.5 明写）。
所以图片域的收藏列表里会同时出现「一本漫画」和「一个画师目录」——
`ImageNode` 自带 `isReadable()` / `isBrowsable()` 两个布尔值，
界面据此决定点进去是阅读器还是子项网格，服务端不需要多做什么。

- [ ] **Step 1: 写会失败的视频域测试**

`src/test/java/com/mymedia/video/VideoFavoriteServiceTest.java`：

```java
package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoFavoriteServiceTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VideoFavoriteService favoriteService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long itemId;
    private Long userId;
    private String username;

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        itemId = insertItem(library.getId(), "沙漠风暴");

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void addsAndRemovesAFavorite() {
        favoriteService.add(userId, itemId);
        assertThat(favoriteService.isFavorite(userId, itemId)).isTrue();

        favoriteService.remove(userId, itemId);
        assertThat(favoriteService.isFavorite(userId, itemId)).isFalse();
    }

    @Test
    void addingTwiceIsIdempotent() {
        favoriteService.add(userId, itemId);
        favoriteService.add(userId, itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_favorite WHERE user_id = ? AND video_item_id = ?",
                Integer.class, userId, itemId)).isEqualTo(1);
    }

    @Test
    void removingSomethingNotFavoritedIsNotAnError() {
        favoriteService.remove(userId, itemId);

        assertThat(favoriteService.isFavorite(userId, itemId)).isFalse();
    }

    @Test
    void favoritesAreStrictlyPerUser() {
        UserAccount other = registrationService.register(
                "o" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(other.getId(), library.getId());

        favoriteService.add(userId, itemId);

        assertThat(favoriteService.isFavorite(other.getId(), itemId)).isFalse();
        assertThat(favoriteService.listItems(other.getId(), 20)).isEmpty();
    }

    @Test
    void listsNewestFirst() throws InterruptedException {
        Long second = insertItem(library.getId(), "雪原突击");
        favoriteService.add(userId, itemId);
        // created_at 的精度是微秒，但两次插入可能落在同一微秒里，睡一下把顺序钉死
        Thread.sleep(10);
        favoriteService.add(userId, second);

        assertThat(favoriteService.listItems(userId, 20))
                .extracting(VideoItem::getId)
                .containsExactly(second, itemId);
    }

    @Test
    void deletingAnItemRemovesItFromEveryonesFavorites() {
        favoriteService.add(userId, itemId);

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_favorite WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void endpointsToggleAndList() throws Exception {
        mockMvc.perform(put("/api/video/items/{id}/favorite", itemId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/video/favorites").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("沙漠风暴"));

        mockMvc.perform(delete("/api/video/items/{id}/favorite", itemId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/video/favorites").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void cannotFavoriteAnItemInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(put("/api/video/items/{id}/favorite", hidden)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 写会失败的图片域测试**

`src/test/java/com/mymedia/image/ImageFavoriteServiceTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImageFavoriteServiceTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImageFavoriteService favoriteService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long nodeId;
    private Long userId;
    private String username;

    /** 与 Task 3 的同名助手一致：收藏测的是用户态，不是建树，所以直接插行造数据。 */
    private Long insertNode(Long libraryId, String name, int directPageCount) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', ?, 'ACTIVE')
                RETURNING id
                """, Long.class, libraryId, name, name, name, directPageCount);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
        nodeId = insertNode(library.getId(), "夏日画集", 24);

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void addsAndRemovesAFavorite() {
        favoriteService.add(userId, nodeId);
        assertThat(favoriteService.isFavorite(userId, nodeId)).isTrue();

        favoriteService.remove(userId, nodeId);
        assertThat(favoriteService.isFavorite(userId, nodeId)).isFalse();
    }

    @Test
    void addingTwiceIsIdempotent() {
        favoriteService.add(userId, nodeId);
        favoriteService.add(userId, nodeId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM image_favorite WHERE user_id = ? AND image_node_id = ?",
                Integer.class, userId, nodeId)).isEqualTo(1);
    }

    @Test
    void removingSomethingNotFavoritedIsNotAnError() {
        favoriteService.remove(userId, nodeId);

        assertThat(favoriteService.isFavorite(userId, nodeId)).isFalse();
    }

    @Test
    void favoritesAreStrictlyPerUser() {
        UserAccount other = registrationService.register(
                "o" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(other.getId(), library.getId());

        favoriteService.add(userId, nodeId);

        assertThat(favoriteService.isFavorite(other.getId(), nodeId)).isFalse();
        assertThat(favoriteService.listNodes(other.getId(), 20)).isEmpty();
    }

    @Test
    void listsNewestFirst() throws InterruptedException {
        Long second = insertNode(library.getId(), "冬日画集", 12);
        favoriteService.add(userId, nodeId);
        Thread.sleep(10);
        favoriteService.add(userId, second);

        assertThat(favoriteService.listNodes(userId, 20))
                .extracting(ImageNode::getId)
                .containsExactly(second, nodeId);
    }

    @Test
    void deletingANodeRemovesItFromEveryonesFavorites() {
        favoriteService.add(userId, nodeId);

        jdbc.update("DELETE FROM image_node WHERE id = ?", nodeId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM image_favorite WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void endpointsToggleAndList() throws Exception {
        mockMvc.perform(put("/api/image/nodes/{id}/favorite", nodeId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/favorites").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("夏日画集"));

        mockMvc.perform(delete("/api/image/nodes/{id}/favorite", nodeId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/favorites").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void cannotFavoriteANodeInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertNode(other.getId(), "看不见的画集", 3);

        mockMvc.perform(put("/api/image/nodes/{id}/favorite", hidden)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsFavoritingAPlainFolderNotJustAReadableBook() {
        // spec §6.5 明写「允许收藏任意节点，包括文件夹」：
        // directPageCount = 0 的节点 isReadable() 为 false，但照样可以被收藏
        Long folderId = insertNode(library.getId(), "某画师", 0);

        favoriteService.add(userId, folderId);

        assertThat(favoriteService.listNodes(userId, 20))
                .extracting(ImageNode::getId)
                .contains(folderId);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='VideoFavoriteServiceTest,ImageFavoriteServiceTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，两个 `*FavoriteService` 不存在。

- [ ] **Step 4: 写迁移脚本**

`src/main/resources/db/migration/V14__favorites.sql`：

```sql
-- ============================================================
-- 收藏。与播放/阅读进度一样是纯用户态：独立成表，绝不塞进媒体表。
-- 这是多用户设计的核心（spec §6.5）。
-- ============================================================

CREATE TABLE video_favorite (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    video_item_id BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_item_id)
);

-- 「我的收藏」按加入时间倒序，这个索引是它的主查询路径
CREATE INDEX idx_video_favorite_user_time ON video_favorite (user_id, created_at DESC);

-- image_favorite 允许收藏任意节点，包括纯目录（spec §6.5 明写）
CREATE TABLE image_favorite (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    image_node_id BIGINT      NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, image_node_id)
);

CREATE INDEX idx_image_favorite_user_time ON image_favorite (user_id, created_at DESC);
```

- [ ] **Step 5: 写视频域收藏**

`src/main/java/com/mymedia/video/VideoFavorite.java`：

```java
package com.mymedia.video;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "video_favorite")
@IdClass(VideoFavorite.Key.class)
public class VideoFavorite {

    /** JPA 复合主键要求一个可序列化、带无参构造器、实现了 equals/hashCode 的类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long videoItemId;

        public Key() {
        }

        public Key(Long userId, Long videoItemId) {
            this.userId = userId;
            this.videoItemId = videoItemId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId)
                    && Objects.equals(videoItemId, key.videoItemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, videoItemId);
        }
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "video_item_id", nullable = false)
    private Long videoItemId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VideoFavorite() {
        // JPA 要求的无参构造器
    }

    VideoFavorite(Long userId, Long videoItemId) {
        this.userId = userId;
        this.videoItemId = videoItemId;
    }

    public Long getUserId() { return userId; }
    public Long getVideoItemId() { return videoItemId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`src/main/java/com/mymedia/video/VideoFavoriteRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface VideoFavoriteRepository extends JpaRepository<VideoFavorite, VideoFavorite.Key> {

    List<VideoFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);
}
```

`src/main/java/com/mymedia/video/VideoFavoriteService.java`：

```java
package com.mymedia.video;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 视频收藏。纯用户态，与播放进度同类：独立成表，绝不塞进 {@code video_item}。
 *
 * <p>增删都做成<b>幂等</b>的：收藏按钮会被反复点，前端也可能重发，
 * 「已经收藏了」和「本来就没收藏」都不该是错误。
 */
@Service
public class VideoFavoriteService {

    private final VideoFavoriteRepository repository;
    private final VideoCatalogService catalogService;

    VideoFavoriteService(VideoFavoriteRepository repository, VideoCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void add(Long userId, Long itemId) {
        if (!repository.existsById(new VideoFavorite.Key(userId, itemId))) {
            repository.save(new VideoFavorite(userId, itemId));
        }
    }

    /**
     * 取消收藏。
     *
     * <p>{@code deleteById} 在实体不存在时<b>静默返回</b>，正是这里想要的幂等语义。
     */
    @Transactional
    public void remove(Long userId, Long itemId) {
        repository.deleteById(new VideoFavorite.Key(userId, itemId));
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long itemId) {
        return repository.existsById(new VideoFavorite.Key(userId, itemId));
    }

    /** 收藏的条目，最近加入的在前。 */
    @Transactional(readOnly = true)
    public List<VideoItem> listItems(Long userId, int limit) {
        List<Long> itemIds = repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(VideoFavorite::getVideoItemId)
                .limit(limit)
                .toList();
        return catalogService.findByIds(itemIds);
    }
}
```

`src/main/java/com/mymedia/video/web/VideoFavoriteController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFavoriteService;
import com.mymedia.video.VideoItem;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class VideoFavoriteController {

    private static final int MAX_LIMIT = 200;

    private final VideoFavoriteService favoriteService;
    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoFavoriteController(VideoFavoriteService favoriteService,
                            VideoCatalogService catalogService,
                            LibraryAccessService accessService,
                            UserQueryService userQueryService) {
        this.favoriteService = favoriteService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/api/video/items/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.add(requireAccess(principal, id), id);
    }

    @DeleteMapping("/api/video/items/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.remove(requireAccess(principal, id), id);
    }

    @GetMapping("/api/video/favorites")
    List<VideoItem> list(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return favoriteService.listItems(currentUserId(principal), Math.clamp(limit, 1, MAX_LIMIT));
    }

    /** 校验访问权并返回当前用户 id。无权访问返回 404，不泄露资源存在性。 */
    private Long requireAccess(UserDetails principal, Long itemId) {
        Long userId = currentUserId(principal);
        VideoItem item = catalogService.getItem(itemId);
        if (!accessService.canAccess(userId, item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
        return userId;
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

- [ ] **Step 6: 写图片域收藏**

`src/main/java/com/mymedia/image/ImageFavorite.java`：

```java
package com.mymedia.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "image_favorite")
@IdClass(ImageFavorite.Key.class)
public class ImageFavorite {

    /** JPA 复合主键要求一个可序列化、带无参构造器、实现了 equals/hashCode 的类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long imageNodeId;

        public Key() {
        }

        public Key(Long userId, Long imageNodeId) {
            this.userId = userId;
            this.imageNodeId = imageNodeId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId)
                    && Objects.equals(imageNodeId, key.imageNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, imageNodeId);
        }
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "image_node_id", nullable = false)
    private Long imageNodeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ImageFavorite() {
        // JPA 要求的无参构造器
    }

    ImageFavorite(Long userId, Long imageNodeId) {
        this.userId = userId;
        this.imageNodeId = imageNodeId;
    }

    public Long getUserId() { return userId; }
    public Long getImageNodeId() { return imageNodeId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`src/main/java/com/mymedia/image/ImageFavoriteRepository.java`：

```java
package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ImageFavoriteRepository extends JpaRepository<ImageFavorite, ImageFavorite.Key> {

    List<ImageFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);
}
```

`src/main/java/com/mymedia/image/ImageFavoriteService.java`：

```java
package com.mymedia.image;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 图片收藏。纯用户态，与阅读进度同类：独立成表，绝不塞进 {@code image_node}。
 *
 * <p>增删都做成<b>幂等</b>的：收藏按钮会被反复点，前端也可能重发，
 * 「已经收藏了」和「本来就没收藏」都不该是错误。
 *
 * <p><b>可以收藏任意节点，包括纯目录</b>（spec §6.5 明写）：
 * 「某画师」这样的中间目录同样值得被收藏，它不需要自己可读。
 * 界面靠 {@code ImageNode.isReadable()} / {@code isBrowsable()} 决定点进去看什么。
 */
@Service
public class ImageFavoriteService {

    private final ImageFavoriteRepository repository;
    private final ImageCatalogService catalogService;

    ImageFavoriteService(ImageFavoriteRepository repository, ImageCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void add(Long userId, Long nodeId) {
        if (!repository.existsById(new ImageFavorite.Key(userId, nodeId))) {
            repository.save(new ImageFavorite(userId, nodeId));
        }
    }

    /**
     * 取消收藏。
     *
     * <p>{@code deleteById} 在实体不存在时<b>静默返回</b>，正是这里想要的幂等语义。
     */
    @Transactional
    public void remove(Long userId, Long nodeId) {
        repository.deleteById(new ImageFavorite.Key(userId, nodeId));
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long nodeId) {
        return repository.existsById(new ImageFavorite.Key(userId, nodeId));
    }

    /** 收藏的节点，最近加入的在前。 */
    @Transactional(readOnly = true)
    public List<ImageNode> listNodes(Long userId, int limit) {
        List<Long> nodeIds = repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ImageFavorite::getImageNodeId)
                .limit(limit)
                .toList();
        return catalogService.findByIds(nodeIds);
    }
}
```

`src/main/java/com/mymedia/image/web/ImageFavoriteController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFavoriteService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ImageFavoriteController {

    private static final int MAX_LIMIT = 200;

    private final ImageFavoriteService favoriteService;
    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageFavoriteController(ImageFavoriteService favoriteService,
                            ImageCatalogService catalogService,
                            LibraryAccessService accessService,
                            UserQueryService userQueryService) {
        this.favoriteService = favoriteService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/api/image/nodes/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.add(requireAccess(principal, id), id);
    }

    @DeleteMapping("/api/image/nodes/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.remove(requireAccess(principal, id), id);
    }

    @GetMapping("/api/image/favorites")
    List<ImageNode> list(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return favoriteService.listNodes(currentUserId(principal), Math.clamp(limit, 1, MAX_LIMIT));
    }

    /** 校验访问权并返回当前用户 id。无权访问返回 404，不泄露资源存在性。 */
    private Long requireAccess(UserDetails principal, Long nodeId) {
        Long userId = currentUserId(principal);
        ImageNode node = catalogService.getNode(nodeId);
        if (!accessService.canAccess(userId, node.getLibraryId())) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
        return userId;
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

- [ ] **Step 7: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='VideoFavoriteServiceTest,ImageFavoriteServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V14__favorites.sql src/main/java/com/mymedia src/test/java/com/mymedia
git commit -m "feat: 添加两个域的收藏

纯用户态，与播放/阅读进度同类：独立成表，绝不塞进媒体表。
仍然住在各自的领域模块里——抽到公共模块会立刻需要一个'目标类型'多态列，
那正是本项目一直在避免的东西。

增删都是幂等的：收藏按钮会被反复点，'已经收藏了'不该是错误。
图片域允许收藏纯目录，spec 6.5 明写。"
```

Expected: `EXIT=0`，`VideoFavoriteServiceTest` 8 个、`ImageFavoriteServiceTest` 9 个用例通过。

---

## Task 8: 分享链接的创建、撤销与查询

**Files:**
- Create: `src/main/resources/db/migration/V15__share_link.sql`
- Create: `src/main/java/com/mymedia/library/ShareLink.java`
- Create: `src/main/java/com/mymedia/library/ShareLinkRepository.java`
- Create: `src/main/java/com/mymedia/library/ShareGrant.java`
- Create: `src/main/java/com/mymedia/library/ShareLinkDto.java`
- Create: `src/main/java/com/mymedia/library/ShareLinkService.java`
- Create: `src/main/java/com/mymedia/library/ShareLinkController.java`
- Create: `src/main/java/com/mymedia/video/web/VideoShareLinkController.java`
- Create: `src/main/java/com/mymedia/image/web/ImageShareLinkController.java`
- Test: `src/test/java/com/mymedia/library/ShareLinkServiceTest.java`
- Test: `src/test/java/com/mymedia/library/ShareLinkControllerTest.java`

**Interfaces:**
- Consumes: `MediaLibrary`、`LibraryAccessService`、`UserQueryService`、`NotFoundException`（计划 01）、`VideoCatalogService`（计划 03）、`ImageCatalogService`（计划 04）
- Produces:
  - `public record ShareGrant(Long shareLinkId, Long libraryId, Long videoItemId, Long imageNodeId, boolean passwordProtected, Instant expiresAt)` — 带 `isVideo()` / `isImage()`
  - `public class ShareLink` — getter：`getId/getToken/getLibraryId/getVideoItemId/getImageNodeId/getExpiresAt/getCreatedBy/getCreatedAt/getRevokedAt`、`boolean isPasswordProtected()`
  - `public final class ShareLinkDto` — `public record CreateRequest(String password, Integer expiresInDays)`、`public record Response(...)` 带 `static Response from(ShareLink)`
  - `public class ShareLinkService`
    - `public ShareLink createForVideoItem(Long creatorId, Long libraryId, Long videoItemId, ShareLinkDto.CreateRequest request)`
    - `public ShareLink createForImageNode(Long creatorId, Long libraryId, Long imageNodeId, ShareLinkDto.CreateRequest request)`
    - `public List<ShareLink> listCreatedBy(Long creatorId)`
    - `public void revoke(Long creatorId, Long shareLinkId)`
    - `public ShareGrant resolve(String token)`
  - `POST /api/video/items/{id}/share`、`POST /api/image/nodes/{id}/share`、`GET /api/shares`、`DELETE /api/shares/{id}`

### 创建端点为什么在领域模块，管理端点为什么在 `library`

`ShareLinkService` 住 `library` 是定好的（它只存标量 id，不引任何领域类型，
`library → shared, user` 的依赖表一个字不用改）。但**创建一条链接必须先确认
「这个条目真的存在、而且你有权访问它」**——那需要 `VideoCatalogService`，
而 `library` 永远不许依赖 `video`。

所以切成两半：

| 端点 | 住哪 | 为什么 |
|---|---|---|
| `POST /api/video/items/{id}/share` | `video.web` | 要校验条目归属与访问权 |
| `POST /api/image/nodes/{id}/share` | `image.web` | 同上 |
| `GET /api/shares`、`DELETE /api/shares/{id}` | `library` | 只认 `created_by` 与 `id`，不需要知道目标是什么 |

这和 Task 6 的标签是同一个形状（URL 按领域切分，实现放在依赖方向允许的地方），
只是这次连实现类也不得不分开——因为 `library` 的依赖比 `metadata` 更窄。

**没有为此引入 SPI 倒置**：让 `library` 定义一个 `ShareTargetValidator` 接口、
`video` 与 `image` 各实现一份，换来的只是把两个薄控制器变成两个薄适配器加一个接口。
ADR-004 已经论证过这类倒置的适用条件（"加第三个域不改核心代码"），这里不满足。

### 列表只返回标量，标题由前端各自去取

`GET /api/shares` 返回 `{id, token, domain, targetId, ...}`，**不含条目标题与封面**。
要带上它们，`library` 就得同时认识 `video_item` 与 `image_node` 两个模型——
代价远大于前端按 `domain` 各自多发一次请求。这也是 spec §5.2「两个域互不感知」
在接口层的一个具体体现。

### 令牌是 32 字节 `SecureRandom`，Base64URL 无填充

43 个字符，128 位以上的熵，放进 URL 不需要转义。**不要用 `UUID.randomUUID()`**：
它只有 122 位熵，而且形状上一眼能认出来是 UUID，会让人误以为它是内部 id。

令牌**只在创建时返回一次是不必要的**——它不是密码，撤销随时可做，
所以 `GET /api/shares` 照常带上它（用户要能把链接再复制一遍）。

### 目标外键是 `ON DELETE CASCADE`

条目被删（例如整个媒体库被删）时，指向它的分享链接跟着消失，不留悬空记录。
这与 spec §6.6 给 `scrape_candidate` 的理由完全一致，也是「两个可空外键 + CHECK
恰有一个非空」这个选择的主要收益——多态列做不到这件事。

- [ ] **Step 1: 写会失败的服务层测试**

`src/test/java/com/mymedia/library/ShareLinkServiceTest.java`：

```java
package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLinkServiceTest extends AbstractIntegrationTest {

    @Autowired
    ShareLinkService shareLinkService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long itemId;
    private Long ownerId;

    private static final ShareLinkDto.CreateRequest PLAIN =
            new ShareLinkDto.CreateRequest(null, null);

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        itemId = insertItem(library.getId(), "沙漠风暴");

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private ShareLink create() {
        return shareLinkService.createForVideoItem(ownerId, library.getId(), itemId, PLAIN);
    }

    @Test
    void tokenIsUrlSafeAndLongEnoughToResistGuessing() {
        String token = create().getToken();

        // 32 字节 Base64URL 无填充 = 43 个字符，字符集只有 A-Za-z0-9-_
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void everyLinkGetsADistinctToken() {
        assertThat(create().getToken()).isNotEqualTo(create().getToken());
    }

    @Test
    void resolvesToAGrantPointingAtTheSharedItem() {
        ShareLink link = create();

        ShareGrant grant = shareLinkService.resolve(link.getToken());

        assertThat(grant.shareLinkId()).isEqualTo(link.getId());
        assertThat(grant.libraryId()).isEqualTo(library.getId());
        assertThat(grant.videoItemId()).isEqualTo(itemId);
        assertThat(grant.imageNodeId()).isNull();
        assertThat(grant.isVideo()).isTrue();
        assertThat(grant.passwordProtected()).isFalse();
    }

    @Test
    void aRevokedTokenResolvesToTheSameNotFoundAsAnUnknownOne() {
        ShareLink link = create();
        shareLinkService.revoke(ownerId, link.getId());

        // 区分「不存在」与「已失效」等于告诉扫链接的人「这个令牌曾经存在」
        assertThatThrownBy(() -> shareLinkService.resolve(link.getToken()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> shareLinkService.resolve("HHiVX3lHTuKrH0P-Y8sJ0dnhkFYCkDBPa2b7pt2X0Kg"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anExpiredTokenIsNotFound() {
        ShareLink link = create();
        jdbc.update("UPDATE share_link SET expires_at = ? WHERE id = ?",
                Instant.now().minus(1, ChronoUnit.MINUTES), link.getId());

        assertThatThrownBy(() -> shareLinkService.resolve(link.getToken()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void expiresInDaysIsTurnedIntoAnAbsoluteInstant() {
        ShareLink link = shareLinkService.createForVideoItem(
                ownerId, library.getId(), itemId, new ShareLinkDto.CreateRequest(null, 7));

        assertThat(link.getExpiresAt())
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    void aPasswordIsStoredHashedAndNeverExposed() {
        ShareLink link = shareLinkService.createForVideoItem(
                ownerId, library.getId(), itemId, new ShareLinkDto.CreateRequest("hunter2", null));

        assertThat(link.isPasswordProtected()).isTrue();
        assertThat(shareLinkService.resolve(link.getToken()).passwordProtected()).isTrue();

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM share_link WHERE id = ?", String.class, link.getId());
        assertThat(stored).isNotNull().doesNotContain("hunter2").startsWith("{bcrypt}");
    }

    @Test
    void onlyTheCreatorCanRevoke() {
        ShareLink link = create();
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);

        assertThatThrownBy(() -> shareLinkService.revoke(stranger.getId(), link.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(shareLinkService.resolve(link.getToken())).isNotNull();
    }

    @Test
    void listOnlyReturnsMyOwnLinks() {
        create();
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        shareLinkService.createForVideoItem(stranger.getId(), library.getId(), itemId, PLAIN);

        assertThat(shareLinkService.listCreatedBy(ownerId))
                .hasSize(1)
                .allMatch(link -> link.getCreatedBy().equals(ownerId));
    }

    @Test
    void revokedLinksStayInTheListSoTheUserCanSeeWhatHappened() {
        ShareLink link = create();
        shareLinkService.revoke(ownerId, link.getId());

        assertThat(shareLinkService.listCreatedBy(ownerId))
                .singleElement()
                .satisfies(revoked -> assertThat(revoked.getRevokedAt()).isNotNull());
    }

    @Test
    void deletingTheTargetItemTakesItsShareLinksWithIt() {
        create();

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM share_link WHERE library_id = ?",
                Integer.class, library.getId())).isZero();
    }

    @Test
    void theDatabaseRefusesALinkThatPointsAtBothDomains() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO share_link (token, library_id, video_item_id, image_node_id, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, "tok" + UUID.randomUUID(), library.getId(), itemId, itemId, ownerId))
                .hasMessageContaining("ck_share_link_single_target");
    }
}
```

- [ ] **Step 2: 写会失败的端点测试**

`src/test/java/com/mymedia/library/ShareLinkControllerTest.java`：

> 这个测试**只经 HTTP 与 JSON**，不 import 任何 `video` / `image` 类型，
> 所以它虽然验证的是两个领域模块里的控制器，住在 `library` 的测试包里
> 也不会给模块依赖图添任何一条边。

```java
package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ShareLinkControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private String username;
    private Long userId;
    private Long itemId;
    private Long nodeId;

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    private Long insertNode(Long libraryId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', 8, 'ACTIVE')
                RETURNING id
                """, Long.class, libraryId, name, name, name);
    }

    @BeforeEach
    void setUp() {
        MediaLibrary videoLibrary = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        MediaLibrary imageLibrary = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.IMAGE, "/tmp/" + UUID.randomUUID());
        itemId = insertItem(videoLibrary.getId(), "沙漠风暴");
        nodeId = insertNode(imageLibrary.getId(), "夏日画集");

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, videoLibrary.getId());
        accessService.grant(userId, imageLibrary.getId());
    }

    @Test
    void createsAShareLinkForAVideoItem() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("VIDEO"))
                .andExpect(jsonPath("$.targetId").value(itemId))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.passwordProtected").value(false));
    }

    @Test
    void createsAShareLinkForAnImageNode() throws Exception {
        mockMvc.perform(post("/api/image/nodes/{id}/share", nodeId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\",\"expiresInDays\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("IMAGE"))
                .andExpect(jsonPath("$.targetId").value(nodeId))
                .andExpect(jsonPath("$.passwordProtected").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void theResponseNeverCarriesThePasswordHash() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void cannotShareSomethingInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(post("/api/video/items/{id}/share", hidden)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiresInDaysIsRejectedWhenOutOfRange() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\":9999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsAndRevokesThroughTheManagementEndpoints() throws Exception {
        String body = mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long shareId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Integer.class)
                .longValue();

        mockMvc.perform(get("/api/shares").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(shareId));

        mockMvc.perform(delete("/api/shares/{id}", shareId).with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/shares").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
    }

    @Test
    void revokingSomeoneElsesLinkIsNotFound() throws Exception {
        String body = mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString();
        Long shareId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Integer.class)
                .longValue();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(delete("/api/shares/{id}", shareId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }
}
```

> `com.jayway.jsonpath.JsonPath` 随 `spring-boot-starter-test` 的 `json-path`
> 传递引入，MockMvc 的 `jsonPath(...)` 用的就是它，所以不需要新增依赖。

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ShareLinkServiceTest,ShareLinkControllerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`ShareLinkService` 不存在。

- [ ] **Step 4: 写迁移脚本**

`src/main/resources/db/migration/V15__share_link.sql`：

```sql
-- ============================================================
-- 分享链接。令牌本身就是凭证（bearer capability）：
-- 拿到令牌的人不需要账号也能看，因此令牌必须足够长且随机。
--
-- 目标用「两个可空外键 + CHECK 恰有一个非空」而非 (target_type, target_id)
-- 多态列（spec §6.2）：多态外键在 PostgreSQL 里建不了引用完整性约束，
-- 删掉条目就会留下指向虚空的分享链接。
-- ============================================================

CREATE TABLE share_link (
    id            BIGSERIAL PRIMARY KEY,
    -- 32 字节 SecureRandom 的 Base64URL 无填充形式，43 个字符
    token         VARCHAR(64) NOT NULL,
    library_id    BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    video_item_id BIGINT      REFERENCES video_item (id) ON DELETE CASCADE,
    image_node_id BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,
    -- 可空：不设密码的链接是常态。存的是 {bcrypt} 前缀的委派编码
    password_hash TEXT,
    -- 可空：NULL 表示永不过期
    expires_at    TIMESTAMPTZ,
    created_by    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 撤销是打标记而不是删行：用户要能在列表里看见「这条我撤了」
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT ck_share_link_single_target
        CHECK (num_nonnulls(video_item_id, image_node_id) = 1)
);

ALTER TABLE share_link ADD CONSTRAINT uq_share_link_token UNIQUE (token);

-- 「我创建的分享」按时间倒序，这是管理页的主查询路径
CREATE INDEX idx_share_link_creator ON share_link (created_by, created_at DESC);
```

- [ ] **Step 5: 写实体、仓储与令牌解析结果**

`src/main/java/com/mymedia/library/ShareLink.java`：

```java
package com.mymedia.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条分享链接。
 *
 * <p><b>只存标量 id</b>：{@code videoItemId} / {@code imageNodeId} 是裸的 {@code Long}，
 * 不是 {@code @ManyToOne}。这不是偷懒——{@code library} 模块不许依赖 {@code video}
 * 与 {@code image}，映射成关联就必须 import 它们的实体类，
 * {@code ApplicationModules.verify()} 会当场拒绝。
 * 引用完整性由数据库的外键负责，不由 JPA 负责。
 */
@Entity
@Table(name = "share_link")
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String token;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    @Column(name = "video_item_id", updatable = false)
    private Long videoItemId;

    @Column(name = "image_node_id", updatable = false)
    private Long imageNodeId;

    @Column(name = "password_hash", updatable = false)
    private String passwordHash;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ShareLink() {
        // JPA 要求的无参构造器
    }

    ShareLink(String token, Long libraryId, Long videoItemId, Long imageNodeId,
              String passwordHash, Instant expiresAt, Long createdBy) {
        this.token = token;
        this.libraryId = libraryId;
        this.videoItemId = videoItemId;
        this.imageNodeId = imageNodeId;
        this.passwordHash = passwordHash;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public Long getLibraryId() { return libraryId; }
    public Long getVideoItemId() { return videoItemId; }
    public Long getImageNodeId() { return imageNodeId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** 只说「有没有密码」，不交出哈希。 */
    public boolean isPasswordProtected() {
        return passwordHash != null;
    }

    /** 哈希是 package-private 的：只有本模块的服务需要它去做校验。 */
    String passwordHash() {
        return passwordHash;
    }

    boolean isUsableAt(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    void revoke(Instant when) {
        if (revokedAt == null) {
            this.revokedAt = when;
        }
    }
}
```

`src/main/java/com/mymedia/library/ShareLinkRepository.java`：

```java
package com.mymedia.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByCreatedByOrderByCreatedAtDesc(Long createdBy);
}
```

`src/main/java/com/mymedia/library/ShareGrant.java`：

```java
package com.mymedia.library;

import java.time.Instant;

/**
 * 一个已校验通过的令牌所代表的临时只读授权。
 *
 * <p>它是 {@code library} 交给两个领域模块的<b>唯一</b>凭据：领域模块拿到它就知道
 * 「允许访问哪个库的哪一个目标」，而不需要知道令牌长什么样、有没有过期、
 * 密码对不对——那些在 {@link ShareLinkService#resolve} 里已经判完了。
 *
 * <p>两个目标字段恰有一个非空，与数据库上的 CHECK 约束一一对应。
 */
public record ShareGrant(
        Long shareLinkId,
        Long libraryId,
        Long videoItemId,
        Long imageNodeId,
        boolean passwordProtected,
        Instant expiresAt) {

    public boolean isVideo() {
        return videoItemId != null;
    }

    public boolean isImage() {
        return imageNodeId != null;
    }
}
```

- [ ] **Step 6: 写 DTO 与服务**

`src/main/java/com/mymedia/library/ShareLinkDto.java`：

```java
package com.mymedia.library;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 分享链接的对外形状。
 *
 * <p><b>是 public 的</b>（`LibraryDto` 是 package-private）：创建端点住在
 * {@code video.web} 与 {@code image.web}，它们要绑定同一份请求体、返回同一份响应体。
 */
public final class ShareLinkDto {

    private ShareLinkDto() {
    }

    /**
     * @param password       为空表示不设密码
     * @param expiresInDays  为空表示永不过期。用「几天后」而不是绝对时刻，
     *                       是为了不必和客户端争论时钟与时区
     */
    public record CreateRequest(
            @Size(max = 128) String password,
            @Min(1) @Max(365) Integer expiresInDays) {
    }

    /**
     * 响应里<b>不含条目标题与封面</b>：带上它们，{@code library} 就得同时认识
     * {@code video_item} 与 {@code image_node} 两个模型。前端按 {@code domain}
     * 各自去取一次，代价小得多。
     */
    public record Response(
            Long id,
            String token,
            LibraryDomain domain,
            Long libraryId,
            Long targetId,
            boolean passwordProtected,
            Instant expiresAt,
            Instant createdAt,
            Instant revokedAt) {

        public static Response from(ShareLink link) {
            boolean video = link.getVideoItemId() != null;
            return new Response(
                    link.getId(),
                    link.getToken(),
                    video ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                    link.getLibraryId(),
                    video ? link.getVideoItemId() : link.getImageNodeId(),
                    link.isPasswordProtected(),
                    link.getExpiresAt(),
                    link.getCreatedAt(),
                    link.getRevokedAt());
        }
    }
}
```

`src/main/java/com/mymedia/library/ShareLinkService.java`：

```java
package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 分享链接的创建、撤销与令牌解析。
 *
 * <p>住在 {@code library} 而不是某个领域模块：一条链接指向哪个域是它的<b>数据</b>，
 * 不是它的<b>行为</b>。本类从不 import 任何 {@code video} / {@code image} 类型，
 * 因此 {@code library → shared, user} 的依赖表不需要任何改动。
 *
 * <p><b>创建时不校验目标是否存在</b>：那需要领域知识。校验由调用方
 * （{@code VideoShareLinkController} / {@code ImageShareLinkController}）在
 * 自己的模块里完成，数据库的外键是最后一道防线——目标 id 不存在时
 * INSERT 会直接违反外键。
 */
@Service
public class ShareLinkService {

    /** 32 字节 → Base64URL 无填充 43 字符，熵远高于 UUID 的 122 位。 */
    private static final int TOKEN_BYTES = 32;

    private final ShareLinkRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    ShareLinkService(ShareLinkRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ShareLink createForVideoItem(Long creatorId, Long libraryId, Long videoItemId,
                                        ShareLinkDto.CreateRequest request) {
        return create(creatorId, libraryId, videoItemId, null, request);
    }

    @Transactional
    public ShareLink createForImageNode(Long creatorId, Long libraryId, Long imageNodeId,
                                        ShareLinkDto.CreateRequest request) {
        return create(creatorId, libraryId, null, imageNodeId, request);
    }

    private ShareLink create(Long creatorId, Long libraryId, Long videoItemId, Long imageNodeId,
                             ShareLinkDto.CreateRequest request) {
        String hash = (request.password() == null || request.password().isBlank())
                ? null
                : passwordEncoder.encode(request.password());
        Instant expiresAt = request.expiresInDays() == null
                ? null
                : Instant.now().plus(Duration.ofDays(request.expiresInDays()));

        return repository.save(new ShareLink(newToken(), libraryId, videoItemId, imageNodeId,
                hash, expiresAt, creatorId));
    }

    @Transactional(readOnly = true)
    public List<ShareLink> listCreatedBy(Long creatorId) {
        return repository.findByCreatedByOrderByCreatedAtDesc(creatorId);
    }

    /**
     * 撤销。
     *
     * <p>撤销别人的链接返回 404 而不是 403：403 会确认「这个 id 确实存在」。
     * 与项目其余部分同一条纪律。
     */
    @Transactional
    public void revoke(Long creatorId, Long shareLinkId) {
        ShareLink link = repository.findById(shareLinkId)
                .filter(candidate -> candidate.getCreatedBy().equals(creatorId))
                .orElseThrow(() -> new NotFoundException("找不到分享链接 id=" + shareLinkId));
        link.revoke(Instant.now());
    }

    /**
     * 把令牌解析成一份临时只读授权。
     *
     * <p><b>无效、过期、已撤销一律抛同一个 {@link NotFoundException}</b>：
     * 区分它们等于告诉扫链接的人「这个令牌曾经存在」。
     * 密码是否正确不在这里判——那要等客户端拿票据来（Task 9）。
     */
    @Transactional(readOnly = true)
    public ShareGrant resolve(String token) {
        ShareLink link = repository.findByToken(token)
                .filter(candidate -> candidate.isUsableAt(Instant.now()))
                .orElseThrow(() -> new NotFoundException("分享链接不存在或已失效"));

        return new ShareGrant(link.getId(), link.getLibraryId(),
                link.getVideoItemId(), link.getImageNodeId(),
                link.isPasswordProtected(), link.getExpiresAt());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
```

- [ ] **Step 7: 写三个控制器**

`src/main/java/com/mymedia/library/ShareLinkController.java`：

```java
package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享链接的管理端点：列出我创建的、撤销其中一条。
 *
 * <p>只认 {@code created_by} 与 {@code id}，不需要知道目标是视频还是图片，
 * 所以它可以住在 {@code library}。创建端点做不到这一点（要校验目标的访问权），
 * 因此分别住在两个领域模块里。
 *
 * <p><b>路径是 {@code /api/shares}（复数），免登录访问用的是
 * {@code /api/share/{token}}（单数）。</b>两者不会互相匹配——
 * Spring 的路径模式按整段比较，{@code /api/share/**} 不匹配 {@code /api/shares}。
 * Task 9 有一个测试专门钉住这件事。
 */
@RestController
@RequestMapping("/api/shares")
class ShareLinkController {

    private final ShareLinkService shareLinkService;
    private final UserQueryService userQueryService;

    ShareLinkController(ShareLinkService shareLinkService, UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<ShareLinkDto.Response> list(@AuthenticationPrincipal UserDetails principal) {
        return shareLinkService.listCreatedBy(currentUserId(principal)).stream()
                .map(ShareLinkDto.Response::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        shareLinkService.revoke(currentUserId(principal), id);
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

`src/main/java/com/mymedia/video/web/VideoShareLinkController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为一个视频条目创建分享链接。
 *
 * <p>住在 {@code video} 而不是 {@code library}：创建前必须确认
 * 「这个条目存在、而且你有权访问它」，那需要 {@link VideoCatalogService}，
 * 而 {@code library} 永远不许依赖 {@code video}。
 */
@RestController
class VideoShareLinkController {

    private final ShareLinkService shareLinkService;
    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoShareLinkController(ShareLinkService shareLinkService,
                             VideoCatalogService catalogService,
                             LibraryAccessService accessService,
                             UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/api/video/items/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    ShareLinkDto.Response create(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody ShareLinkDto.CreateRequest request) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        VideoItem item = catalogService.getItem(id);
        if (!accessService.canAccess(userId, item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + id);
        }
        return ShareLinkDto.Response.from(
                shareLinkService.createForVideoItem(userId, item.getLibraryId(), id, request));
    }
}
```

`src/main/java/com/mymedia/image/web/ImageShareLinkController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为一个图片节点创建分享链接。理由同 {@code VideoShareLinkController}。
 *
 * <p>可以分享<b>任意节点</b>，包括纯目录——分享一个画师目录和分享一本漫画
 * 是同一件事，Task 9 的访问端点会按节点自身的能力决定给出什么。
 */
@RestController
class ImageShareLinkController {

    private final ShareLinkService shareLinkService;
    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageShareLinkController(ShareLinkService shareLinkService,
                             ImageCatalogService catalogService,
                             LibraryAccessService accessService,
                             UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/api/image/nodes/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    ShareLinkDto.Response create(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody ShareLinkDto.CreateRequest request) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        ImageNode node = catalogService.getNode(id);
        if (!accessService.canAccess(userId, node.getLibraryId())) {
            throw new NotFoundException("找不到图片节点 id=" + id);
        }
        return ShareLinkDto.Response.from(
                shareLinkService.createForImageNode(userId, node.getLibraryId(), id, request));
    }
}
```

- [ ] **Step 8: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ShareLinkServiceTest,ShareLinkControllerTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V15__share_link.sql src/main/java/com/mymedia src/test/java/com/mymedia/library
git commit -m "feat: 添加分享链接的创建、撤销与查询

ShareLinkService 住 library 且只存标量 id：映射成 @ManyToOne 就必须 import
video/image 的实体类，Modulith 会当场拒绝。引用完整性由数据库外键负责。

创建端点被迫分到两个领域模块——创建前要校验目标的访问权，
那是 library 不许知道的事。没有为此引入 SPI 倒置，理由见 ADR-004 的适用条件。

无效/过期/已撤销的令牌返回同一个 404：区分它们等于确认令牌曾经存在。"
```

Expected: `EXIT=0`，`ShareLinkServiceTest` 12 个、`ShareLinkControllerTest` 7 个用例通过。

---

## Task 9: 分享链接的免登录访问

**Files:**
- Modify: `src/main/java/com/mymedia/user/SecurityConfig.java`（放行 `/api/share/**`）
- Create: `src/main/java/com/mymedia/library/ShareTicket.java`
- Modify: `src/main/java/com/mymedia/library/ShareLinkService.java`（新增 `unlock` 与 `resolveUnlocked`）
- Modify: `src/main/java/com/mymedia/library/ShareLinkDto.java`（新增三个 record）
- Create: `src/main/java/com/mymedia/library/ShareAccessController.java`
- Modify: `src/main/java/com/mymedia/video/VideoStreamService.java`（新增 `locateForShare`，抽出 `toTarget`）
- Create: `src/main/java/com/mymedia/video/web/VideoRangeResponder.java`
- Modify: `src/main/java/com/mymedia/video/web/VideoStreamController.java`（改用 `VideoRangeResponder`）
- Create: `src/main/java/com/mymedia/video/web/VideoShareController.java`
- Modify: `src/main/java/com/mymedia/image/ImagePageService.java`（新增 `locateForShare`，抽出 `toTarget`）
- Create: `src/main/java/com/mymedia/image/web/ImageShareController.java`
- Test: `src/test/java/com/mymedia/library/ShareTicketTest.java`
- Test: `src/test/java/com/mymedia/video/VideoShareControllerTest.java`
- Test: `src/test/java/com/mymedia/image/ImageShareControllerTest.java`

**Interfaces:**
- Consumes: `ShareGrant`、`ShareLinkService.resolve`（Task 8）、`VideoStreamService.StreamTarget`（计划 03）、`ImagePageService.PageTarget`（计划 04）、`VideoCatalogDto`、`ImageNodeDto`、`ImageBrowseService`
- Produces:
  - `class ShareTicket`（package-private）— `String issue(String token, Instant now, Instant linkExpiresAt)`、`boolean verify(String token, String ticket, Instant now)`
  - `ShareLinkService` 新增：
    - `public Optional<String> unlock(String token, String rawPassword)`
    - `public ShareGrant resolveUnlocked(String token, String ticket)`
  - `ShareLinkDto` 新增：`public record UnlockRequest(String password)`、`public record UnlockResponse(String ticket)`、`public record PublicView(LibraryDomain domain, boolean requiresPassword, Instant expiresAt)`
  - `VideoStreamService.locateForShare(ShareGrant grant, Long fileId)` → `StreamTarget`
  - `ImagePageService.locateForShare(ShareGrant grant, Long fileId)` → `PageTarget`
  - `class VideoRangeResponder`（package-private）— `static ResponseEntity<StreamingResponseBody> respond(StreamTarget, String rangeHeader, String ifRange)`
  - `GET /api/share/{token}`、`POST /api/share/{token}/unlock`
  - `GET /api/share/{token}/video/item`、`GET /api/share/{token}/video/stream/{fileId}`
  - `GET /api/share/{token}/image/node`、`GET /api/share/{token}/image/pages/{fileId}`

### 令牌是能力，不是会话

`/api/share/**` 整段 `permitAll`。安全性完全由令牌的熵（32 字节）承担，
这是 bearer capability 的标准形态：**持有即授权，不需要知道你是谁**。
撤销靠 `revoked_at`，过期靠 `expires_at`，两者都在 `resolve` 里判。

⚠ **`/api/shares`（复数，需登录）不能被 `/api/share/**` 放行。**
Spring 的路径模式按整段比较，`/api/share/**` 不匹配 `/api/shares`——
但这件事全靠"我以为是这样"太危险，下面有一个测试专门钉住它。

### 带密码的链接为什么用 HMAC 票据而不是会话

一本漫画翻 20 页就是 20 次请求。若每次都用 bcrypt 重验密码，
每次约 100 ms，阅读器会卡成幻灯片。而开会话就意味着给未登录访客建服务端状态，
和"无状态 REST + 令牌即凭证"的整体形态打架。

**票据是一张自证明的短期通行证**：

```
ticket = <过期时刻的 epoch 秒> + "." + base64url( HMAC-SHA256(secret, token + ":" + 过期时刻) )
```

服务端不存任何东西，校验就是重算一遍 HMAC 再做**定时安全比较**
（`MessageDigest.isEqual`，不是 `String.equals`——后者会在第一个不同字节处提前返回，
把比较耗时变成一个可测量的旁路信道）。

票据有效期取 `min(配置的 TTL, 链接自身的过期时刻)`：**票据绝不能比链接活得久**，
否则撤销之外还得再撤票据。链接被撤销时票据仍然有效？不会——每次请求都会先
`resolve(token)`，撤销在那一步就 404 了，票据只决定"密码这一关过没过"。

### 密钥缺省时随机生成，并且说出来

`mymedia.share.secret` 没配就每次启动随机生成一个，并打 WARN 日志：
重启后旧票据全部失效，用户需要重新输一次密码。对单实例自托管这是可接受的默认，
比"要求用户在跑起来之前先想一个密钥"更符合"一键启动"的交付目标。
**但它必须是一条明确的日志，不是一个沉默的行为。**

### 包含性校验放在服务里，不放在控制器里

`locateForShare(grant, fileId)` 自己检查"这个文件属于被分享的那个条目/子树"。
放控制器里也能跑，但控制器可能会有第二个入口，而服务是必经之路——
**把不变式钉在唯一必经的那一层**。图片域的子树判断用物化路径前缀匹配，
一次字符串比较，不查库。

- [ ] **Step 1: 写票据的纯单元测试**

`src/test/java/com/mymedia/library/ShareTicketTest.java`：

```java
package com.mymedia.library;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShareTicketTest {

    private final ShareTicket ticket = new ShareTicket("test-secret-do-not-use", Duration.ofHours(12));

    private static final String TOKEN = "HHiVX3lHTuKrH0P-Y8sJ0dnhkFYCkDBPa2b7pt2X0Kg";

    @Test
    void aFreshTicketVerifiesAgainstTheTokenItWasIssuedFor() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify(TOKEN, issued, now)).isTrue();
    }

    @Test
    void aTicketIssuedForOneTokenDoesNotWorkOnAnother() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify("Zm9vYmFyLXNvbWUtb3RoZXItc2hhcmUtdG9rZW4tMTIzNDU2", issued, now))
                .isFalse();
    }

    @Test
    void anExpiredTicketIsRejected() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify(TOKEN, issued, now.plus(13, ChronoUnit.HOURS))).isFalse();
    }

    @Test
    void theTicketNeverOutlivesTheLinkItself() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        Instant linkExpiry = now.plus(30, ChronoUnit.MINUTES);

        String issued = ticket.issue(TOKEN, now, linkExpiry);

        assertThat(ticket.verify(TOKEN, issued, now.plus(29, ChronoUnit.MINUTES))).isTrue();
        assertThat(ticket.verify(TOKEN, issued, now.plus(31, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void tamperingWithTheExpiryInvalidatesTheSignature() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        String issued = ticket.issue(TOKEN, now, null);
        String signature = issued.substring(issued.indexOf('.') + 1);

        // 把过期时刻往后推十年，签名照旧——必须被拒
        String forged = (now.plus(3650, ChronoUnit.DAYS).getEpochSecond()) + "." + signature;

        assertThat(ticket.verify(TOKEN, forged, now)).isFalse();
    }

    @Test
    void aDifferentSecretProducesADifferentSignature() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        ShareTicket other = new ShareTicket("another-secret", Duration.ofHours(12));

        assertThat(other.verify(TOKEN, ticket.issue(TOKEN, now, null), now)).isFalse();
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        assertThat(ticket.verify(TOKEN, null, now)).isFalse();
        assertThat(ticket.verify(TOKEN, "", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "no-dot-here", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "notanumber.c2ln", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "1755000000.@@@not-base64@@@", now)).isFalse();
    }

    @Test
    void aBlankConfiguredSecretFallsBackToARandomOne() {
        ShareTicket randomised = new ShareTicket("", Duration.ofHours(12));
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        // 自己签自己验必须通过；跨实例不通过（重启后旧票据失效，这是已知取舍）
        String issued = randomised.issue(TOKEN, now, null);
        assertThat(randomised.verify(TOKEN, issued, now)).isTrue();
        assertThat(new ShareTicket("", Duration.ofHours(12)).verify(TOKEN, issued, now)).isFalse();
    }
}
```

- [ ] **Step 2: 写票据实现**

`src/main/java/com/mymedia/library/ShareTicket.java`：

```java
package com.mymedia.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 带密码的分享链接解锁后签发的短期通行证。
 *
 * <p>形状：{@code <过期时刻的 epoch 秒>.<base64url(HMAC-SHA256)>}，
 * 签的是 {@code token + ":" + 过期时刻}。<b>服务端不存任何东西</b>——
 * 校验就是重算一遍再比。
 *
 * <p>为什么不是会话：一本漫画翻 20 页就是 20 次请求，每次重验 bcrypt 约 100 ms，
 * 阅读器会卡死；而给未登录访客建服务端状态又和「无状态 REST + 令牌即凭证」打架。
 */
@Component
class ShareTicket {

    private static final Logger log = LoggerFactory.getLogger(ShareTicket.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;

    ShareTicket(@Value("${mymedia.share.secret:}") String configuredSecret,
                @Value("${mymedia.share.ticket-ttl:PT12H}") Duration ttl) {
        this.ttl = ttl;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("未配置 mymedia.share.secret，本次启动使用随机密钥："
                    + "重启后带密码的分享链接需要访客重新输入一次密码");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 签发一张票据。
     *
     * @param linkExpiresAt 链接自身的过期时刻，可为 null（永不过期）。
     *                      <b>票据绝不能比链接活得久</b>，所以取二者较早的那个。
     */
    String issue(String token, Instant now, Instant linkExpiresAt) {
        Instant expiry = now.plus(ttl);
        if (linkExpiresAt != null && linkExpiresAt.isBefore(expiry)) {
            expiry = linkExpiresAt;
        }
        long epochSecond = expiry.getEpochSecond();
        return epochSecond + "." + sign(token, epochSecond);
    }

    /** 任何形状不对、过期、签名不符的输入一律 false，绝不抛异常。 */
    boolean verify(String token, String ticket, Instant now) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            return false;
        }
        long epochSecond;
        try {
            epochSecond = Long.parseLong(ticket.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.ofEpochSecond(epochSecond).isBefore(now)) {
            return false;
        }
        // 定时安全比较：String.equals 在第一个不同字节处就返回，
        // 把比较耗时变成一个可测量的旁路信道
        return MessageDigest.isEqual(
                sign(token, epochSecond).getBytes(StandardCharsets.UTF_8),
                ticket.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String token, long epochSecond) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal(
                    (token + ":" + epochSecond).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 必须支持 " + ALGORITHM, e);
        }
    }
}
```

- [ ] **Step 3: 扩展 `ShareLinkService` 与 `ShareLinkDto`**

在 `src/main/java/com/mymedia/library/ShareLinkDto.java` 里追加三个 record：

```java
    public record UnlockRequest(@Size(max = 128) String password) {
    }

    public record UnlockResponse(String ticket) {
    }

    /**
     * 未登录访客能看到的全部信息。
     *
     * <p><b>不含目标 id、标题与库名</b>：访客只需要知道"这是视频还是图片"
     * 和"要不要密码"，其余靠对应领域的 share 端点给出。
     */
    public record PublicView(LibraryDomain domain, boolean requiresPassword, Instant expiresAt) {
    }
```

在 `src/main/java/com/mymedia/library/ShareLinkService.java` 里，构造参数增加
`ShareTicket shareTicket`（连同同名字段），并追加两个方法与所需 import
（`java.util.Optional`、`org.springframework.http.HttpStatus`、
`org.springframework.web.server.ResponseStatusException`）：

```java
    /**
     * 校验密码并签发票据。
     *
     * <p>返回 {@code Optional.empty()} 表示密码不对——<b>把它翻成 401 是控制器的事</b>，
     * 服务层不认识 HTTP 状态码。
     *
     * <p>不设密码的链接调用本方法同样返回空：没有密码就不需要票据，
     * 客户端直接访问即可。
     */
    @Transactional(readOnly = true)
    public Optional<String> unlock(String token, String rawPassword) {
        ShareLink link = repository.findByToken(token)
                .filter(candidate -> candidate.isUsableAt(Instant.now()))
                .orElseThrow(() -> new NotFoundException("分享链接不存在或已失效"));

        if (link.passwordHash() == null || rawPassword == null
                || !passwordEncoder.matches(rawPassword, link.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(shareTicket.issue(token, Instant.now(), link.getExpiresAt()));
    }

    /**
     * 解析令牌，并确认带密码的链接已经解锁。
     *
     * <p>三种失败各有各的状态码，区别是有意的：
     * <ul>
     *   <li>令牌无效 / 过期 / 已撤销 → <b>404</b>（不确认它是否存在过）</li>
     *   <li>需要密码但没带票据、或票据不对 → <b>401</b>（此时对方已经证明持有令牌，
     *       告诉它"这里需要密码"不泄露任何东西，反而是界面弹出密码框的依据）</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public ShareGrant resolveUnlocked(String token, String ticket) {
        ShareGrant grant = resolve(token);
        if (grant.passwordProtected() && !shareTicket.verify(token, ticket, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "分享链接需要密码");
        }
        return grant;
    }
```

- [ ] **Step 4: 放行 `/api/share/**`**

修改 `src/main/java/com/mymedia/user/SecurityConfig.java` 的 `authorizeHttpRequests`：

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        // 分享链接：令牌本身就是凭证（bearer capability），整段免登录。
                        // 注意是单数 /api/share/**；管理端点 /api/shares（复数）
                        // 不被这个模式匹配，仍然需要登录。ShareAccessControllerTest 钉住了这件事。
                        .requestMatchers("/api/share/**").permitAll()
                        .anyRequest().authenticated())
```

- [ ] **Step 5: 写免登录的元信息与解锁端点**

`src/main/java/com/mymedia/library/ShareAccessController.java`：

```java
package com.mymedia.library;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分享链接的免登录入口：这条链接是什么、要不要密码、拿票据。
 *
 * <p>内容本身由两个领域模块各自的 share 控制器给出
 * （{@code /api/share/{token}/video/**} 与 {@code /api/share/{token}/image/**}）——
 * {@code library} 不认识内容长什么样。
 */
@RestController
@RequestMapping("/api/share/{token}")
class ShareAccessController {

    private final ShareLinkService shareLinkService;

    ShareAccessController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    /** 不需要票据也能调：客户端正是靠它知道"要不要弹密码框"。 */
    @GetMapping
    ShareLinkDto.PublicView describe(@PathVariable String token) {
        ShareGrant grant = shareLinkService.resolve(token);
        return new ShareLinkDto.PublicView(
                grant.isVideo() ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                grant.passwordProtected(),
                grant.expiresAt());
    }

    @PostMapping("/unlock")
    ShareLinkDto.UnlockResponse unlock(@PathVariable String token,
                                       @Valid @RequestBody ShareLinkDto.UnlockRequest request) {
        return shareLinkService.unlock(token, request.password())
                .map(ShareLinkDto.UnlockResponse::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码不正确"));
    }
}
```

- [ ] **Step 6: 给两个域的定位服务加分享入口**

修改 `src/main/java/com/mymedia/video/VideoStreamService.java`：把 `locate` 的后半段
抽成私有的 `toTarget`，再加一个 `locateForShare`。改完后这三个方法长这样
（`contentTypeOf` 与 `StreamTarget` 保持原样不动）：

```java
    /**
     * 定位物理文件并校验访问权。
     *
     * <p>无权访问一律抛 {@link NotFoundException} 而非权限异常——
     * 返回 403 会泄露「这个 id 确实存在」。
     */
    @Transactional(readOnly = true)
    public StreamTarget locate(Long userId, Long fileId) {
        VideoFile videoFile = catalogService.getFile(fileId);
        ScannedFile scanned = scannedFiles.getById(videoFile.getScannedFileId());

        if (!accessService.canAccess(userId, scanned.getLibraryId())) {
            throw new NotFoundException("找不到视频文件 id=" + fileId);
        }
        return toTarget(videoFile, scanned);
    }

    /**
     * 分享链接的定位入口：<b>不查 {@code library_access}</b>，
     * 访问控制已经由令牌完成（{@code ShareLinkService.resolveUnlocked}）。
     *
     * <p>但**包含性必须在这里查**：一张指向条目 A 的分享链接不能被用来播条目 B 的文件。
     * 这道校验放在服务里而不是控制器里——控制器可能会有第二个入口，服务是必经之路。
     */
    @Transactional(readOnly = true)
    public StreamTarget locateForShare(ShareGrant grant, Long fileId) {
        VideoFile videoFile = catalogService.getFile(fileId);
        if (!Objects.equals(videoFile.getItemId(), grant.videoItemId())) {
            throw new NotFoundException("找不到视频文件 id=" + fileId);
        }
        return toTarget(videoFile, scannedFiles.getById(videoFile.getScannedFileId()));
    }

    private StreamTarget toTarget(VideoFile videoFile, ScannedFile scanned) {
        if (scanned.getStatus() == ScannedFileStatus.MISSING) {
            throw new NotFoundException("文件当前不可用（可能所在磁盘未挂载）: " + scanned.getRelativePath());
        }

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path path = root.resolve(scanned.getRelativePath());

        // ETag 由 id + 大小 + 修改时间构成：文件内容变了 ETag 必变，
        // 客户端的断点续传请求才会被正确地判为过期。
        String etag = "\"" + scanned.getId() + "-" + scanned.getSizeBytes()
                + "-" + scanned.getMtime().toEpochMilli() + "\"";

        return new StreamTarget(path, scanned.getSizeBytes(), etag,
                scanned.getMtime(), contentTypeOf(scanned.getExtension()));
    }
```

新增 import：`com.mymedia.library.ShareGrant`、`java.util.Objects`。

修改 `src/main/java/com/mymedia/image/ImagePageService.java`，同样的手法：

```java
    @Transactional(readOnly = true)
    public PageTarget locate(Long userId, Long fileId) {
        ImageFile page = catalogService.getFile(fileId);
        ScannedFile scanned = scannedFiles.getById(page.getScannedFileId());

        if (!accessService.canAccess(userId, scanned.getLibraryId())) {
            throw new NotFoundException("找不到图片 id=" + fileId);
        }
        return toTarget(page, scanned);
    }

    /**
     * 分享链接的定位入口。访问控制由令牌完成，此处只查包含性。
     *
     * <p>图片域分享的是<b>一个节点及其整棵子树</b>：分享「某画师」应当能翻到
     * 它下面每一本。判断用物化路径前缀匹配——一次字符串比较，不查库，
     * 这正是存物化路径的收益之一。
     */
    @Transactional(readOnly = true)
    public PageTarget locateForShare(ShareGrant grant, Long fileId) {
        ImageFile page = catalogService.getFile(fileId);
        String sharedPath = catalogService.getNode(grant.imageNodeId()).getMaterializedPath();

        if (!catalogService.getNode(page.getNodeId()).getMaterializedPath().startsWith(sharedPath)) {
            throw new NotFoundException("找不到图片 id=" + fileId);
        }
        return toTarget(page, scannedFiles.getById(page.getScannedFileId()));
    }

    private PageTarget toTarget(ImageFile page, ScannedFile scanned) {
        if (scanned.getStatus() == ScannedFileStatus.MISSING) {
            throw new NotFoundException(
                    "文件当前不可用（可能所在磁盘未挂载）: " + scanned.getRelativePath());
        }

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path path = root.resolve(scanned.getRelativePath());

        String etag = "\"" + page.getId() + "-" + scanned.getSizeBytes()
                + "-" + scanned.getMtime().toEpochMilli() + "\"";

        String nameForType = page.getArchiveEntryName() == null
                ? scanned.getRelativePath()
                : page.getArchiveEntryName();

        return new PageTarget(path, page.getArchiveEntryName(),
                page.getArchiveEntryName() == null ? scanned.getSizeBytes() : -1,
                etag, scanned.getMtime(), contentTypeOf(nameForType));
    }
```

新增 import：`com.mymedia.library.ShareGrant`。

- [ ] **Step 7: 把 Range 应答抽成可复用的一段**

`src/main/java/com/mymedia/video/web/VideoRangeResponder.java`：

```java
package com.mymedia.video.web;

import com.mymedia.video.VideoStreamService;
import com.mymedia.video.range.RangeParser;
import com.mymedia.video.range.RangeResolution;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardOpenOption;

/**
 * 把一个 {@link VideoStreamService.StreamTarget} 按 Range 语义写成 HTTP 应答。
 *
 * <p>抽出来的唯一理由是<b>它有两个入口</b>：登录后的 {@code /api/video/stream/{fileId}}
 * 与分享链接的 {@code /api/share/{token}/video/stream/{fileId}}。
 * 206 / Content-Range / If-Range / 416 这套语义抄第二遍必然抄漏一条。
 */
final class VideoRangeResponder {

    private VideoRangeResponder() {
    }

    static ResponseEntity<StreamingResponseBody> respond(VideoStreamService.StreamTarget target,
                                                         String rangeHeader,
                                                         String ifRange) {
        // If-Range 校验：ETag 对不上说明客户端手里的是旧版本，
        // 此时必须忽略 Range 返回完整内容 —— 否则客户端会把新旧字节拼在一起，
        // 得到一个损坏的文件。
        String effectiveRange = (ifRange != null && !ifRange.equals(target.etag()))
                ? null
                : rangeHeader;

        RangeResolution resolution = RangeParser.resolve(effectiveRange, target.sizeBytes());

        return switch (resolution) {
            case RangeResolution.Full full -> ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.ETAG, target.etag())
                    .header(HttpHeaders.CONTENT_TYPE, target.contentType())
                    .contentLength(full.length())
                    .body(writer(target, 0, full.length()));

            case RangeResolution.Partial partial -> ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.ETAG, target.etag())
                    .header(HttpHeaders.CONTENT_TYPE, target.contentType())
                    .header(HttpHeaders.CONTENT_RANGE, partial.contentRangeHeader())
                    .contentLength(partial.contentLength())
                    .body(writer(target, partial.start(), partial.contentLength()));

            case RangeResolution.Unsatisfiable unsatisfiable ->
                    ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(HttpHeaders.CONTENT_RANGE, unsatisfiable.contentRangeHeader())
                            .build();
        };
    }

    /**
     * 用 {@code FileChannel.transferTo} 零拷贝写出：
     * 数据在内核态直接从页缓存送到 socket，不经过 JVM 堆。
     * 配合虚拟线程，阻塞 I/O 不占用平台线程。
     */
    private static StreamingResponseBody writer(VideoStreamService.StreamTarget target,
                                                long position, long count) {
        return (OutputStream out) -> {
            try (FileChannel channel = FileChannel.open(target.path(), StandardOpenOption.READ);
                 WritableByteChannel sink = Channels.newChannel(out)) {

                long remaining = count;
                long offset = position;
                while (remaining > 0) {
                    long transferred = channel.transferTo(offset, remaining, sink);
                    if (transferred <= 0) {
                        break;
                    }
                    offset += transferred;
                    remaining -= transferred;
                }
            } catch (IOException e) {
                // 客户端拖动进度条会中断连接，这是正常行为不是错误。
                // 此处静默结束，避免日志被刷屏。
            }
        };
    }
}
```

把 `src/main/java/com/mymedia/video/web/VideoStreamController.java` 整个换成下面这份
（`stream` 方法瘦成三行，`writer` 与 switch 都搬走了）：

```java
package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/video/stream")
class VideoStreamController {

    private final VideoStreamService streamService;
    private final UserQueryService userQueryService;

    VideoStreamController(VideoStreamService streamService, UserQueryService userQueryService) {
        this.streamService = streamService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{fileId}")
    ResponseEntity<StreamingResponseBody> stream(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {

        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        return VideoRangeResponder.respond(
                streamService.locate(userId, fileId), rangeHeader, ifRange);
    }
}
```

- [ ] **Step 8: 写两个域的 share 控制器**

`src/main/java/com/mymedia/video/web/VideoShareController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.library.ShareGrant;
import com.mymedia.library.ShareLinkService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * 分享链接下的视频访问。<b>整段免登录</b>（{@code SecurityConfig} 里 permitAll）。
 *
 * <p>没有复制任何播放逻辑：定位走 {@code VideoStreamService.locateForShare}，
 * Range 应答走 {@code VideoRangeResponder}，与登录后的端点是同一段代码。
 * 唯一的差别是「凭什么允许你看」——一个查 {@code library_access}，
 * 一个查令牌与包含性。
 */
@RestController
@RequestMapping("/api/share/{token}/video")
class VideoShareController {

    /** 票据请求头。带密码的链接解锁后由客户端在每次请求上带回。 */
    private static final String TICKET_HEADER = "X-Share-Ticket";

    private final ShareLinkService shareLinkService;
    private final VideoCatalogService catalogService;
    private final VideoStreamService streamService;

    VideoShareController(ShareLinkService shareLinkService,
                         VideoCatalogService catalogService,
                         VideoStreamService streamService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.streamService = streamService;
    }

    @GetMapping("/item")
    VideoCatalogDto.ItemDetail item(@PathVariable String token,
                                    @RequestHeader(value = TICKET_HEADER, required = false)
                                    String ticket) {
        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        Long itemId = requireVideoTarget(grant);

        List<VideoCatalogDto.FileSummary> files = catalogService.filesOf(itemId).stream()
                .map(VideoCatalogDto.FileSummary::from)
                .toList();

        // 分享视图不给分组：一条链接指向一个条目，剧集分组属于库内浏览的形态
        return new VideoCatalogDto.ItemDetail(
                VideoCatalogDto.ItemSummary.from(catalogService.getItem(itemId)),
                List.of(),
                files);
    }

    @GetMapping("/stream/{fileId}")
    ResponseEntity<StreamingResponseBody> stream(
            @PathVariable String token,
            @PathVariable Long fileId,
            @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {

        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        requireVideoTarget(grant);

        return VideoRangeResponder.respond(
                streamService.locateForShare(grant, fileId), rangeHeader, ifRange);
    }

    /**
     * 一张图片链接被拿来打视频端点时返回 404。
     *
     * <p>它确实存在，但在这个 URL 下不存在——而且既然对方拿错了域，
     * 告诉它「这是张图片链接」也没有意义。
     */
    private Long requireVideoTarget(ShareGrant grant) {
        if (!grant.isVideo()) {
            throw new com.mymedia.shared.NotFoundException("分享链接不存在或已失效");
        }
        return grant.videoItemId();
    }
}
```

`src/main/java/com/mymedia/image/web/ImageShareController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageBrowseService;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImagePageService;
import com.mymedia.library.ShareGrant;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 分享链接下的图片访问。<b>整段免登录</b>。
 *
 * <p>分享的是<b>一个节点及其整棵子树</b>：分享「某画师」应当能一路翻到
 * 它下面每一本。{@code nodeId} 参数允许在子树内导航，越界一律 404。
 */
@RestController
@RequestMapping("/api/share/{token}/image")
class ImageShareController {

    private static final String TICKET_HEADER = "X-Share-Ticket";

    private final ShareLinkService shareLinkService;
    private final ImageCatalogService catalogService;
    private final ImageBrowseService browseService;
    private final ImagePageService pageService;

    ImageShareController(ShareLinkService shareLinkService,
                         ImageCatalogService catalogService,
                         ImageBrowseService browseService,
                         ImagePageService pageService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.browseService = browseService;
        this.pageService = pageService;
    }

    /**
     * 子树内的一个节点：它自己、它的子节点、它直接持有的页。
     *
     * @param nodeId 省略时就是被分享的那个节点
     */
    @GetMapping("/node")
    ShareNodeView node(@PathVariable String token,
                       @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
                       @RequestParam(value = "nodeId", required = false) Long nodeId) {
        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        ImageNode node = requireWithinShare(grant, nodeId);

        return new ShareNodeView(
                ImageNodeDto.NodeSummary.from(node),
                browseService.childNodes(node.getLibraryId(), node.getId()).stream()
                        .map(ImageNodeDto.NodeSummary::from)
                        .toList(),
                catalogService.pagesOf(node.getId()).stream()
                        .map(ImageNodeDto.PageSummary::from)
                        .toList());
    }

    @GetMapping("/pages/{fileId}")
    ResponseEntity<InputStreamResource> page(
            @PathVariable String token,
            @PathVariable Long fileId,
            @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {

        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        if (!grant.isImage()) {
            throw new NotFoundException("分享链接不存在或已失效");
        }
        ImagePageService.PageTarget target = pageService.locateForShare(grant, fileId);

        if (target.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpServletResponse.SC_NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, target.etag())
                .header(HttpHeaders.CONTENT_TYPE, target.contentType())
                .body(new InputStreamResource(pageService.open(target)));
    }

    /** 请求的节点必须落在被分享的子树里；否则 404，不区分「不存在」与「越界」。 */
    private ImageNode requireWithinShare(ShareGrant grant, Long nodeId) {
        if (!grant.isImage()) {
            throw new NotFoundException("分享链接不存在或已失效");
        }
        ImageNode shared = catalogService.getNode(grant.imageNodeId());
        if (nodeId == null || nodeId.equals(shared.getId())) {
            return shared;
        }
        ImageNode requested = catalogService.getNode(nodeId);
        if (!requested.getMaterializedPath().startsWith(shared.getMaterializedPath())) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
        return requested;
    }

    /** 分享视图的一屏：节点自己 + 子节点 + 页。 */
    record ShareNodeView(ImageNodeDto.NodeSummary node,
                         List<ImageNodeDto.NodeSummary> children,
                         List<ImageNodeDto.PageSummary> pages) {
    }
}
```

- [ ] **Step 9: 写两个域的集成测试**

`src/test/java/com/mymedia/video/VideoShareControllerTest.java`：

```java
package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoShareControllerTest extends AbstractIntegrationTest {

    private static final byte[] CONTENT = "0123456789abcdef".getBytes();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ShareLinkService shareLinkService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private Long itemId;
    private Long fileId;
    private Long ownerId;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(libraryRoot.resolve("movie.mp4"), CONTENT);
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());

        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'movie.mp4', ?, now(), 'mp4') RETURNING id
                """, Long.class, library.getId(), (long) CONTENT.length);
        itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '沙漠风暴', '沙漠风暴') RETURNING id
                """, Long.class, library.getId());
        fileId = jdbc.queryForObject("""
                INSERT INTO video_file (item_id, scanned_file_id, role)
                VALUES (?, ?, 'MAIN') RETURNING id
                """, Long.class, itemId, scannedId);

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private String share(String password) {
        return shareLinkService.createForVideoItem(ownerId, library.getId(), itemId,
                new ShareLinkDto.CreateRequest(password, null)).getToken();
    }

    @Test
    void anAnonymousVisitorCanReadTheItemAndStreamIt() throws Exception {
        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.title").value("沙漠风暴"))
                .andExpect(jsonPath("$.files[0].id").value(fileId));

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, fileId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    void rangeRequestsWorkThroughTheShareEndpointToo() throws Exception {
        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, fileId)
                        .header(HttpHeaders.RANGE, "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4-7/16"))
                .andExpect(content().bytes("4567".getBytes()));
    }

    @Test
    void aPasswordProtectedLinkNeedsATicket() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isUnauthorized());

        // 但元信息端点必须能读——客户端正是靠它知道要弹密码框
        mockMvc.perform(get("/api/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresPassword").value(true))
                .andExpect(jsonPath("$.domain").value("VIDEO"));

        String unlock = mockMvc.perform(post("/api/share/{token}/unlock", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ticket = com.jayway.jsonpath.JsonPath.parse(unlock).read("$.ticket");

        mockMvc.perform(get("/api/share/{token}/video/item", token)
                        .header("X-Share-Ticket", ticket))
                .andExpect(status().isOk());
    }

    @Test
    void theWrongPasswordIsUnauthorizedAndNoTicketComesBack() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(post("/api/share/{token}/unlock", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aForgedTicketDoesNotWork() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(get("/api/share/{token}/video/item", token)
                        .header("X-Share-Ticket", "99999999999.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedLinkStopsWorkingImmediately() throws Exception {
        String token = share(null);
        Long shareId = jdbc.queryForObject(
                "SELECT id FROM share_link WHERE token = ?", Long.class, token);
        shareLinkService.revoke(ownerId, shareId);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anExpiredLinkStopsWorking() throws Exception {
        String token = share(null);
        jdbc.update("UPDATE share_link SET expires_at = ? WHERE token = ?",
                Instant.now().minusSeconds(60), token);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void aLinkForOneItemCannotStreamAnotherItemsFile() throws Exception {
        Long otherItem = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '雪原突击', '雪原突击') RETURNING id
                """, Long.class, library.getId());
        Long otherScanned = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'other.mp4', 16, now(), 'mp4') RETURNING id
                """, Long.class, library.getId());
        Long otherFile = jdbc.queryForObject("""
                INSERT INTO video_file (item_id, scanned_file_id, role)
                VALUES (?, ?, 'MAIN') RETURNING id
                """, Long.class, otherItem, otherScanned);

        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, otherFile))
                .andExpect(status().isNotFound());
    }

    @Test
    void theManagementEndpointIsStillBehindLogin() throws Exception {
        // /api/share/** 是 permitAll，/api/shares 不是。
        // 路径模式按整段比较，两者不会互相匹配——这一条必须钉住。
        mockMvc.perform(get("/api/shares"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownTokenIsNotFound() throws Exception {
        mockMvc.perform(get("/api/share/{token}/video/item",
                        "Zm9vYmFyLXNvbWUtb3RoZXItc2hhcmUtdG9rZW4tMTIz"))
                .andExpect(status().isNotFound());
    }
}
```

`src/test/java/com/mymedia/image/ImageShareControllerTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImageShareControllerTest extends AbstractIntegrationTest {

    private static final byte[] PIXEL = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ShareLinkService shareLinkService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private Long artistId;
    private Long bookId;
    private Long pageId;
    private Long outsiderPageId;
    private Long ownerId;

    /** 建一个节点；parentPath 为 null 表示建在根上。 */
    private Long insertNode(Long parentId, String parentPath, String name, int directPageCount) {
        Long id = jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        parent_id, name, sort_key, source_kind,
                                        direct_page_count, status)
                VALUES (?, '', '/' || ? || '/', ?, ?, ?, ?, 'DIRECTORY', ?, 'ACTIVE')
                RETURNING id
                """, Long.class, library.getId(), name,
                parentPath == null ? 0 : 1, parentId, name, name, directPageCount);
        // 物化路径要含自己的 id，所以只能插完再回填（与计划 04 的建树逻辑一致）
        String path = (parentPath == null ? "/" : parentPath) + id + "/";
        jdbc.update("UPDATE image_node SET materialized_path = ? WHERE id = ?", path, id);
        return id;
    }

    private String pathOf(Long nodeId) {
        return jdbc.queryForObject(
                "SELECT materialized_path FROM image_node WHERE id = ?", String.class, nodeId);
    }

    private Long insertPage(Long nodeId, String fileName, int pageIndex) throws Exception {
        Files.write(libraryRoot.resolve(fileName), PIXEL);
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, ?, ?, now(), 'jpg') RETURNING id
                """, Long.class, library.getId(), fileName, (long) PIXEL.length);
        return jdbc.queryForObject("""
                INSERT INTO image_file (node_id, scanned_file_id, page_index, sort_key)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, nodeId, scannedId, pageIndex, fileName);
    }

    @BeforeEach
    void setUp() throws Exception {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                libraryRoot.toString());

        artistId = insertNode(null, null, "某画师", 0);
        bookId = insertNode(artistId, pathOf(artistId), "第一本", 1);
        pageId = insertPage(bookId, "p001.jpg", 0);

        Long outsider = insertNode(null, null, "别人", 1);
        outsiderPageId = insertPage(outsider, "x001.jpg", 0);

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private String share(Long nodeId) {
        return shareLinkService.createForImageNode(ownerId, library.getId(), nodeId,
                new ShareLinkDto.CreateRequest(null, null)).getToken();
    }

    @Test
    void sharingAFolderExposesItsChildren() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/node", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.name").value("某画师"))
                .andExpect(jsonPath("$.children", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.children[0].id").value(bookId))
                .andExpect(jsonPath("$.pages", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void navigatingIntoAChildWithinTheSharedSubtreeIsAllowed() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/node", token).param("nodeId", bookId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.pages[0].id").value(pageId));
    }

    @Test
    void aPageInsideTheSharedSubtreeCanBeRead() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/pages/{fileId}", token, pageId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PIXEL));
    }

    @Test
    void nodesOutsideTheSharedSubtreeAreNotFound() throws Exception {
        String token = share(bookId);

        mockMvc.perform(get("/api/share/{token}/image/node", token)
                        .param("nodeId", artistId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void pagesOutsideTheSharedSubtreeAreNotFound() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/pages/{fileId}", token, outsiderPageId))
                .andExpect(status().isNotFound());
    }

    @Test
    void animageTokenCannotBeUsedOnTheVideoEndpoints() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }
}
```

> 上面的 `insertNode` 先插后回填物化路径，与计划 04 的建树逻辑一致
> （路径要含自己的 id，而 id 是 `BIGSERIAL`，插入前拿不到）。
> 若计划 04 落地时把这个语义改成了别的形状，按那边的实际写法调整这个助手即可，
> 测试的断言不需要动。

- [ ] **Step 10: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ShareTicketTest,VideoShareControllerTest,ImageShareControllerTest,VideoStreamControllerTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia
git commit -m "feat: 分享链接的免登录访问

/api/share/** 整段 permitAll——令牌就是凭证。管理端点是 /api/shares（复数），
路径模式按整段比较不会被误放行，有测试钉住。

带密码的链接签发 HMAC 票据而不是开会话：一本漫画翻 20 页就是 20 次 bcrypt，
每次 100ms 会让阅读器卡死。票据无状态、定时安全比较、绝不比链接活得久。

包含性校验放在 locateForShare 里而不是控制器里：控制器可能有第二个入口，
服务是必经之路。Range 应答抽成 VideoRangeResponder，两个入口共用同一段代码。"
```

Expected: `EXIT=0`，`ShareTicketTest` 8 个、`VideoShareControllerTest` 10 个、
`ImageShareControllerTest` 6 个用例通过，`VideoStreamControllerTest` 原有 10 个仍然通过
（重构没有改变它的行为，这就是重构的验收条件）。

---

## Task 10: `SampledHash` 提升到 `shared`、上传会话与秒传

**Files:**
- Move: `src/main/java/com/mymedia/scan/SampledHash.java` → `src/main/java/com/mymedia/shared/SampledHash.java`（改 public）
- Move: `src/test/java/com/mymedia/scan/SampledHashTest.java` → `src/test/java/com/mymedia/shared/SampledHashTest.java`
- Modify: `src/main/java/com/mymedia/scan/RelocationDetector.java`（补 import）
- Create: `src/main/java/com/mymedia/scan/ScannedFileHashService.java`
- Create: `src/main/resources/db/migration/V16__upload.sql`
- Create: `src/main/java/com/mymedia/upload/package-info.java`
- Create: `src/main/java/com/mymedia/upload/UploadProperties.java`
- Create: `src/main/java/com/mymedia/upload/UploadStatus.java`
- Create: `src/main/java/com/mymedia/upload/UploadSession.java`
- Create: `src/main/java/com/mymedia/upload/UploadSessionRepository.java`
- Create: `src/main/java/com/mymedia/upload/SafeFileName.java`
- Create: `src/main/java/com/mymedia/upload/InstantUploadResolver.java`
- Create: `src/main/java/com/mymedia/upload/UploadSessionService.java`
- Create: `src/main/java/com/mymedia/upload/web/UploadDto.java`
- Create: `src/main/java/com/mymedia/upload/web/UploadController.java`
- Modify: `src/main/resources/application.yml`（`mymedia.upload.*` 与 `mymedia.share.*` 的默认值）
- Test: `src/test/java/com/mymedia/upload/SafeFileNameTest.java`
- Test: `src/test/java/com/mymedia/upload/InstantUploadResolverTest.java`
- Test: `src/test/java/com/mymedia/upload/UploadSessionServiceTest.java`

**Interfaces:**
- Consumes: `ScannedFile`、`ScannedFileQueryService`（计划 02）、`LibraryService`、`LibraryAccessService`（计划 01）、`JobQueue`（计划 01）
- Produces:
  - `public final class SampledHash`（`com.mymedia.shared`）— `public static String of(Path file, long sizeBytes) throws IOException`
  - `public class ScannedFileHashService`（`com.mymedia.scan`）
    - `public Optional<ScannedFile> findActiveByContentHash(Long libraryId, String hash)`
    - `public List<ScannedFile> findActiveBySizeWithoutHash(Long libraryId, long sizeBytes, int limit)`
    - `public Optional<String> computeAndStoreHash(Long scannedFileId)`
  - `class SafeFileName`（package-private）— `static String of(String raw)`
  - `public enum UploadStatus { RECEIVING, ASSEMBLING, COMPLETED, FAILED }`
  - `public class UploadSession` — getter：`getId/getUserId/getTargetLibraryId/getFilename/getRelativePath/getTotalSize/getChunkSize/getTotalChunks/getContentHash/getStatus/isInstant/getScannedFileId/getLastError/getCreatedAt/getCompletedAt`
  - `public class UploadSessionService`
    - `public UploadSession create(Long userId, String filename, long totalSize, String contentHash, Long libraryId)`
    - `public UploadSession get(Long userId, Long sessionId)`
  - `POST /api/upload/sessions`、`GET /api/upload/sessions/{id}`
  - Modulith 模块 `upload`，`allowedDependencies = {"shared", "user", "library", "jobs", "scan"}`

### `SampledHash` 搬家，算法一个字节都不许动

计划 02 把它定义成 `com.mymedia.scan` 的 package-private 类，因为当时只有改名检测用它。
现在秒传与合并后校验也要用，而 `upload` 不该为了一个纯算法去依赖 `scan` 的内部实现。
它是**纯算法、不带 `scan` 的任何状态**，与 `NaturalSortKey`、`MaterializedPath` 并列，
正好符合项目既有的**「复用算法，不复用模型」**惯例。

**算法本身一个字节都不能改**——改了会让 `scanned_file.content_hash` 里已有的值全部失效，
改名检测会在下一次扫描时把所有文件都当成新文件：

```
SHA-256( 8 字节大端 sizeBytes ‖ 采样区 )
采样区 = size ≤ 2MB ? 整个文件 : 首 1MB ‖ 尾 1MB
输出 = 小写十六进制 64 字符
```

**这个算法形状是秒传能成立的前提**：浏览器用 `File.slice` 读首尾各 1MB、
`crypto.subtle.digest('SHA-256', …)` 算一次，就能在**不读整个文件**的前提下
拿到和服务端一致的指纹。若当初选的是全量 SHA-256，前端要传一个 20GB 的文件
就得先在本地读完 20GB 才能问「你有没有」——秒传的意义就没了。
这是一处「早先为 A 做的决定，后来在 B 上白捡了一个好处」，值得在讲解文档里说。

### 秒传分两步，因为 `content_hash` 绝大多数是 NULL

计划 02 只在「消失数与新增数都非零」时才算哈希（那是改名检测唯一需要它的时候），
索引也是 `WHERE content_hash IS NOT NULL` 的部分索引。所以直接按哈希查会几乎全部落空：

1. **按 `content_hash` 查**——命中即秒传，一次索引查找。
2. **未命中时**，取同库内 `size_bytes` 相同且 `content_hash IS NULL` 的候选
   （**上限 8 个**），现算它们的哈希**并写回**，再比。

第 2 步让秒传对存量文件也有效，而且**算过的哈希会留下**——
秒传尝试顺带把哈希补齐了，下次更快。代价是最多 8 次 2MB 读（约 16MB I/O）。
上限 8 是为了防病态输入：一个库里可能有几百个大小恰好相同的文件
（同一台设备导出的视频尤其容易撞），不设限就会让创建会话这一个请求读上几 GB。

写进 ADR-007。

### 哈希的写回不放进 `ScannedFileQueryService`

那个类的 javadoc 明写「领域模块通过它拿到文件路径与元信息，**但不能修改物理层状态**」。
秒传要写回哈希，所以新开一个 `ScannedFileHashService`——**它是 `scan` 自己的写入口**，
对外只暴露「给我算一下并存起来」这一个动作，物理层状态的所有权仍在 `scan` 手里。
把写方法塞进查询服务只会让那句 javadoc 变成一句谎话。

### 文件名是外部输入，必须净化

`filename` 会被拼进文件系统路径。`../../etc/passwd`、`C:\Windows\x`、
带控制字符的名字、Windows 上的保留字符 `<>:"|?*`、结尾的点和空格
（Windows 会静默吃掉，导致「写进去的名字」和「读出来的名字」对不上）——
**每一条都由 `SafeFileName` 处理，且每一条都有单元测试**。这是安全边界，
不是"顺手清理一下"。

- [ ] **Step 1: 把 `SampledHash` 搬进 `shared`**

```bash
cd /d/MyMedia
git mv src/main/java/com/mymedia/scan/SampledHash.java src/main/java/com/mymedia/shared/SampledHash.java
git mv src/test/java/com/mymedia/scan/SampledHashTest.java src/test/java/com/mymedia/shared/SampledHashTest.java
```

改 `src/main/java/com/mymedia/shared/SampledHash.java`：包名换成 `com.mymedia.shared`，
类与方法改 public，javadoc 补一段说明它为什么住在这里：

```java
package com.mymedia.shared;

// …（import 原样不动）

/**
 * 内容指纹：文件长度 + 首尾各 1MB 的 SHA-256。
 *
 * <p>对大文件只读首尾各 {@value #SAMPLE_WINDOW} 字节，再把文件长度混入摘要。
 * 全量哈希一个 20GB 视频在机械盘上需要数分钟，扫描承受不起。
 *
 * <p><b>取舍</b>：两个首尾相同、仅中段不同的大文件会得到相同指纹。
 * 改名检测的场景是「同一个文件换了位置」，首尾加长度足以区分不同的媒体文件。
 *
 * <p><b>住在 {@code shared} 的理由</b>：改名检测（{@code scan}）与秒传
 * （{@code upload}）都要用它，而它是纯算法、不带任何模块的状态——
 * 与 {@code NaturalSortKey}、{@code MaterializedPath} 同类，
 * 符合本项目「复用算法，不复用模型」的惯例。
 *
 * <p><b>算法一个字节都不能改</b>：{@code scanned_file.content_hash} 里已经存了按它
 * 算出来的值，改了会让所有既有指纹失效，下一次扫描会把全部文件当成新文件。
 */
public final class SampledHash {

    /** 首尾各采样的字节数。 */
    private static final int SAMPLE_WINDOW = 1024 * 1024;

    private SampledHash() {
    }

    public static String of(Path file, long sizeBytes) throws IOException {
        // …（方法体原样不动）
    }

    // …（两个私有方法原样不动）
}
```

`src/test/java/com/mymedia/shared/SampledHashTest.java` 只改包名声明为
`package com.mymedia.shared;`，用例一个字不动——**算法没变，测试就不该变**，
这正是这次搬家安全的证据。

在 `src/main/java/com/mymedia/scan/RelocationDetector.java` 的 import 区补一行：

```java
import com.mymedia.shared.SampledHash;
```

- [ ] **Step 2: 运行既有测试确认搬家没弄坏任何东西**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='SampledHashTest,RelocationDetectorTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
```

Expected: `EXIT=0`。`SampledHashTest` 8 个、`RelocationDetectorTest` 原有用例全部通过。
**这一步必须先绿再往下走**：后面的所有东西都建立在「指纹算法没变」上。

- [ ] **Step 3: 给 `scan` 加哈希写入口**

`src/main/java/com/mymedia/scan/ScannedFileHashService.java`：

```java
package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SampledHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 内容指纹的查询与按需补算。
 *
 * <p>为什么不并进 {@code ScannedFileQueryService}：那个类明写「不能修改物理层状态」，
 * 而本类要往 {@code scanned_file.content_hash} 里写。物理层状态的所有权仍在
 * {@code scan} 手里，对外只暴露「给我算一下并存起来」这一个动作。
 *
 * <p>调用方是 {@code upload} 的秒传判定：{@code content_hash} 绝大多数是 NULL
 * （计划 02 只在改名检测需要时才算），所以按哈希查会大量落空，
 * 需要「取同尺寸候选现算」这条兜底路径。
 */
@Service
public class ScannedFileHashService {

    private static final Logger log = LoggerFactory.getLogger(ScannedFileHashService.class);

    private final ScannedFileRepository repository;
    private final LibraryService libraryService;

    ScannedFileHashService(ScannedFileRepository repository, LibraryService libraryService) {
        this.repository = repository;
        this.libraryService = libraryService;
    }

    @Transactional(readOnly = true)
    public Optional<ScannedFile> findActiveByContentHash(Long libraryId, String hash) {
        return repository.findByLibraryIdAndContentHashAndStatus(
                libraryId, hash, ScannedFileStatus.ACTIVE);
    }

    /** 同库、同大小、还没算过哈希的 ACTIVE 文件，按 id 升序，最多 {@code limit} 个。 */
    @Transactional(readOnly = true)
    public List<ScannedFile> findActiveBySizeWithoutHash(Long libraryId, long sizeBytes, int limit) {
        return repository.findByLibraryIdAndSizeBytesAndContentHashIsNullAndStatusOrderById(
                libraryId, sizeBytes, ScannedFileStatus.ACTIVE, Limit.of(limit));
    }

    /**
     * 现算一个文件的指纹并写回。
     *
     * <p>读不到文件（外接盘没挂、权限不对）时返回空而不是抛异常：
     * 秒传是<b>优化</b>，读不到就当没命中，正常走分片上传。
     */
    @Transactional
    public Optional<String> computeAndStoreHash(Long scannedFileId) {
        ScannedFile file = repository.findById(scannedFileId)
                .orElseThrow(() -> new NotFoundException("找不到扫描文件 id=" + scannedFileId));
        if (file.getContentHash() != null) {
            return Optional.of(file.getContentHash());
        }
        Path root = Path.of(libraryService.getById(file.getLibraryId()).getRootPath());
        try {
            String hash = SampledHash.of(root.resolve(file.getRelativePath()), file.getSizeBytes());
            file.assignContentHash(hash);
            return Optional.of(hash);
        } catch (IOException e) {
            log.warn("补算内容哈希失败，跳过: {}", file.getRelativePath(), e);
            return Optional.empty();
        }
    }
}
```

在 `src/main/java/com/mymedia/scan/ScannedFileRepository.java` 追加两个查询方法
（需要 import `org.springframework.data.domain.Limit` 与 `java.util.Optional`）：

```java
    Optional<ScannedFile> findByLibraryIdAndContentHashAndStatus(
            Long libraryId, String contentHash, ScannedFileStatus status);

    List<ScannedFile> findByLibraryIdAndSizeBytesAndContentHashIsNullAndStatusOrderById(
            Long libraryId, long sizeBytes, ScannedFileStatus status, Limit limit);
```

> `org.springframework.data.domain.Limit` 是 Spring Data 3.2 起的派生查询限量方式，
> 比在方法名里写死 `First8By…` 灵活，也比 `Pageable` 轻。

- [ ] **Step 4: 写文件名净化的纯单元测试**

`src/test/java/com/mymedia/upload/SafeFileNameTest.java`：

```java
package com.mymedia.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeFileNameTest {

    @Test
    void keepsAnOrdinaryNameIncludingChineseAndSpaces() {
        assertThat(SafeFileName.of("进击的巨人 第01话.mkv")).isEqualTo("进击的巨人 第01话.mkv");
    }

    @Test
    void stripsAnyDirectoryPartOnBothSeparators() {
        assertThat(SafeFileName.of("../../etc/passwd")).isEqualTo("passwd");
        assertThat(SafeFileName.of("C:\\Windows\\System32\\evil.exe")).isEqualTo("evil.exe");
        assertThat(SafeFileName.of("a/b/c/movie.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void rejectsNamesThatAreNothingButDots() {
        assertThatThrownBy(() -> SafeFileName.of(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removesControlCharacters() {
        assertThat(SafeFileName.of("mo\u0000vie\u0007.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void removesCharactersWindowsRefuses() {
        assertThat(SafeFileName.of("a<b>c:d\"e|f?g*h.mp4")).isEqualTo("abcdefgh.mp4");
    }

    @Test
    void trimsTrailingDotsAndSpacesWhichWindowsSilentlyEats() {
        // 不处理的话「写进去的名字」和「读出来的名字」对不上，扫描会当成两个文件
        assertThat(SafeFileName.of("movie.mp4. . ")).isEqualTo("movie.mp4");
        assertThat(SafeFileName.of("  movie.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void truncatesLongNamesButKeepsTheExtension() {
        String name = "长".repeat(400) + ".mkv";

        String safe = SafeFileName.of(name);

        assertThat(safe).hasSize(200).endsWith(".mkv");
    }

    @Test
    void rejectsInputThatSanitisesDownToNothing() {
        assertThatThrownBy(() -> SafeFileName.of("<<<>>>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameWithoutAnExtensionSurvives() {
        assertThat(SafeFileName.of("README")).isEqualTo("README");
    }
}
```

- [ ] **Step 5: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=SafeFileNameTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`SafeFileName` 不存在。

- [ ] **Step 6: 写文件名净化**

`src/main/java/com/mymedia/upload/SafeFileName.java`：

```java
package com.mymedia.upload;

import java.util.Locale;

/**
 * 上传文件名的净化。
 *
 * <p><b>这是安全边界，不是顺手清理。</b>净化后的名字会被拼进媒体库根目录下的
 * 文件系统路径，一个没处理干净的 {@code ../} 就是一次任意写。
 *
 * <p>处理的每一条都对应一个真实问题：
 * <ul>
 *   <li>目录分隔符（两种）—— 路径穿越</li>
 *   <li>纯点名 {@code .} / {@code ..} —— 同上</li>
 *   <li>控制字符 —— 日志注入与不可见的文件名</li>
 *   <li>{@code <>:"|?*} —— Windows 上根本创建不了</li>
 *   <li>结尾的点与空格 —— Windows <b>静默吃掉</b>，导致写进去的名字与读出来的对不上，
 *       下一次扫描会把它当成另一个文件</li>
 *   <li>超长 —— 多数文件系统的单段上限是 255 字节，中文按 UTF-8 是 3 字节/字</li>
 * </ul>
 */
final class SafeFileName {

    /** 200 个字符：中文按 UTF-8 最多 600 字节，仍在 ext4/NTFS 的 255 字节上限内？
     *  不在——所以这里限的是字符数并且留足余量，真正的兜底是文件系统会报错。
     *  取 200 是为了让「明显过长」的名字在进入文件系统之前就被截断。 */
    private static final int MAX_LENGTH = 200;

    private static final String FORBIDDEN = "<>:\"|?*";

    private SafeFileName() {
    }

    static String of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        // 两种分隔符都切：客户端可能是 Windows，服务端可能是 Linux
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        StringBuilder cleaned = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c < 0x20 || c == 0x7F || FORBIDDEN.indexOf(c) >= 0) {
                continue;
            }
            cleaned.append(c);
        }

        // Windows 会静默吃掉结尾的点和空格
        String trimmed = cleaned.toString().strip();
        while (trimmed.endsWith(".") || trimmed.endsWith(" ")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("文件名净化后为空: " + raw);
        }
        return truncate(trimmed);
    }

    /** 截断时保留扩展名——扩展名决定媒体类型判定，丢了它文件就进不了库。 */
    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot <= 12)
                ? name.substring(dot).toLowerCase(Locale.ROOT)
                : "";
        return name.substring(0, MAX_LENGTH - extension.length()) + extension;
    }
}
```

- [ ] **Step 7: 写会失败的秒传与会话测试**

`src/test/java/com/mymedia/upload/InstantUploadResolverTest.java`：

```java
package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SampledHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstantUploadResolverTest extends AbstractIntegrationTest {

    @Autowired
    InstantUploadResolver resolver;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
    }

    /** 造一个真实文件与对应的 scanned_file 行；hash 传 null 模拟「还没算过」。 */
    private Long place(String relativePath, byte[] content, String hash) throws IOException {
        Files.write(libraryRoot.resolve(relativePath), content);
        return jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          content_hash, extension)
                VALUES (?, ?, ?, now(), ?, 'mp4') RETURNING id
                """, Long.class, library.getId(), relativePath, (long) content.length, hash);
    }

    private static byte[] bytes(String seed, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (seed.charAt(i % seed.length()) + i);
        }
        return data;
    }

    @Test
    void hitsDirectlyWhenTheHashIsAlreadyStored() throws Exception {
        byte[] content = bytes("alpha", 4096);
        String hash = SampledHash.of(Files.write(libraryRoot.resolve("tmp.bin"), content),
                content.length);
        Long id = place("a.mp4", content, hash);

        assertThat(resolver.resolve(library.getId(), content.length, hash))
                .get()
                .satisfies(file -> assertThat(file.getId()).isEqualTo(id));
    }

    @Test
    void hitsBySizeCandidateAndBackfillsTheHash() throws Exception {
        byte[] content = bytes("beta", 4096);
        Long id = place("b.mp4", content, null);
        String hash = SampledHash.of(libraryRoot.resolve("b.mp4"), content.length);

        assertThat(resolver.resolve(library.getId(), content.length, hash))
                .get()
                .satisfies(file -> assertThat(file.getId()).isEqualTo(id));

        // 顺带把哈希补齐了——这是这条兜底路径白捡的收益
        assertThat(jdbc.queryForObject(
                "SELECT content_hash FROM scanned_file WHERE id = ?", String.class, id))
                .isEqualTo(hash);
    }

    @Test
    void aSameSizedButDifferentFileIsNotAHit() throws Exception {
        byte[] mine = bytes("gamma", 4096);
        byte[] theirs = bytes("delta", 4096);
        place("c.mp4", theirs, null);
        String myHash = SampledHash.of(Files.write(libraryRoot.resolve("mine.bin"), mine),
                mine.length);

        assertThat(resolver.resolve(library.getId(), mine.length, myHash)).isEmpty();
    }

    @Test
    void looksAtAtMostEightSameSizedCandidates() throws Exception {
        byte[] content = bytes("epsilon", 2048);
        for (int i = 0; i < 12; i++) {
            place("pad" + i + ".mp4", bytes("pad" + i, 2048), null);
        }
        Long target = place("target.mp4", content, null);
        String hash = SampledHash.of(libraryRoot.resolve("target.mp4"), content.length);

        // target 排在第 13 位（按 id 升序），落在 8 个的窗口之外 → 不命中。
        // 这是有意的上界：不设限，一个库里几百个同尺寸文件会让创建会话读上几 GB
        assertThat(resolver.resolve(library.getId(), content.length, hash)).isEmpty();
        assertThat(target).isNotNull();
    }

    @Test
    void aFileFromAnotherLibraryIsNeverAHit() throws Exception {
        byte[] content = bytes("zeta", 4096);
        String hash = SampledHash.of(Files.write(libraryRoot.resolve("tmp2.bin"), content),
                content.length);
        place("d.mp4", content, hash);

        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());

        assertThat(resolver.resolve(other.getId(), content.length, hash)).isEmpty();
    }

    @Test
    void aCandidateWhoseFileVanishedIsSkippedInsteadOfBlowingUp() throws Exception {
        byte[] content = bytes("eta", 4096);
        place("gone.mp4", content, null);
        Files.delete(libraryRoot.resolve("gone.mp4"));

        // 读不到就当没命中，正常走分片上传——秒传是优化，不是必需品
        assertThat(resolver.resolve(library.getId(), content.length, "0".repeat(64))).isEmpty();
    }
}
```

`src/test/java/com/mymedia/upload/UploadSessionServiceTest.java`：

```java
package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SampledHash;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UploadSessionServiceTest extends AbstractIntegrationTest {

    private static final String FAKE_HASH = "a".repeat(64);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UploadSessionService sessionService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private Long userId;
    private String username;

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void createsAReceivingSessionWithServerChosenChunking() {
        // 8MB 分片下，20MB 要 3 片（最后一片 4MB）
        UploadSession session = sessionService.create(userId, "movie.mkv",
                20L * 1024 * 1024, FAKE_HASH, library.getId());

        assertThat(session.getStatus()).isEqualTo(UploadStatus.RECEIVING);
        assertThat(session.getChunkSize()).isEqualTo(8 * 1024 * 1024);
        assertThat(session.getTotalChunks()).isEqualTo(3);
        assertThat(session.isInstant()).isFalse();
    }

    @Test
    void aFileSmallerThanOneChunkStillGetsExactlyOneChunk() {
        UploadSession session = sessionService.create(userId, "small.mp4", 12L, FAKE_HASH,
                library.getId());

        assertThat(session.getTotalChunks()).isEqualTo(1);
    }

    @Test
    void theFilenameIsSanitisedBeforeItIsStored() {
        UploadSession session = sessionService.create(userId, "../../etc/passwd.mp4",
                1024L, FAKE_HASH, library.getId());

        assertThat(session.getFilename()).isEqualTo("passwd.mp4");
    }

    @Test
    void anAlreadyPresentFileCompletesInstantly() throws Exception {
        byte[] content = "the very same bytes".repeat(64).getBytes();
        Files.write(libraryRoot.resolve("existing.mp4"), content);
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'existing.mp4', ?, now(), 'mp4') RETURNING id
                """, Long.class, library.getId(), (long) content.length);
        String hash = SampledHash.of(libraryRoot.resolve("existing.mp4"), content.length);

        UploadSession session = sessionService.create(userId, "copy.mp4",
                content.length, hash, library.getId());

        assertThat(session.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(session.isInstant()).isTrue();
        assertThat(session.getScannedFileId()).isEqualTo(scannedId);
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void cannotUploadIntoALibraryYouCannotAccess() {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());

        assertThatThrownBy(() -> sessionService.create(userId, "x.mp4", 1024L, FAKE_HASH,
                other.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void refusesAFileLargerThanTheConfiguredCeiling() {
        assertThatThrownBy(() -> sessionService.create(userId, "huge.mkv",
                100L * 1024 * 1024 * 1024, FAKE_HASH, library.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readingSomeoneElsesSessionIsNotFound() {
        UploadSession session = sessionService.create(userId, "movie.mkv", 1024L, FAKE_HASH,
                library.getId());
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);

        assertThatThrownBy(() -> sessionService.get(stranger.getId(), session.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theEndpointReportsEverythingTheClientNeedsToStartUploading() throws Exception {
        String body = """
                {"filename":"movie.mkv","totalSize":%d,"contentHash":"%s","targetLibraryId":%d}
                """.formatted(20L * 1024 * 1024, FAKE_HASH, library.getId());

        String response = mockMvc.perform(post("/api/upload/sessions")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.chunkSize").value(8 * 1024 * 1024))
                .andExpect(jsonPath("$.totalChunks").value(3))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        Long id = com.jayway.jsonpath.JsonPath.parse(response).read("$.id", Integer.class)
                .longValue();

        mockMvc.perform(get("/api/upload/sessions/{id}", id).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("movie.mkv"));
    }

    @Test
    void aMalformedHashIsRejectedAtTheEndpoint() throws Exception {
        String body = """
                {"filename":"movie.mkv","totalSize":1024,"contentHash":"nope","targetLibraryId":%d}
                """.formatted(library.getId());

        mockMvc.perform(post("/api/upload/sessions")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='InstantUploadResolverTest,UploadSessionServiceTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`upload` 包还不存在。

- [ ] **Step 9: 写迁移脚本**

`src/main/resources/db/migration/V16__upload.sql`：

```sql
-- ============================================================
-- 分片上传（spec §7.6）。
--
-- 会话与分片是两张表：分片的到达是高频、幂等、可乱序的写入，
-- 而会话是低频的状态机。塞进一张表就意味着每收一片都要改会话行，
-- 在并发上传多片时互相争锁。
-- ============================================================

CREATE TABLE upload_session (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_library_id BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    -- 已经过 SafeFileName 净化，绝不是客户端原样送来的串
    filename          TEXT        NOT NULL,
    -- 合并落库后在媒体库里的相对路径；秒传与未完成时为 NULL
    relative_path     TEXT,
    total_size        BIGINT      NOT NULL,
    -- 分片大小由服务端决定并下发，客户端不许自选：
    -- 分片边界一旦由两边各自计算，断点续传的「第 N 片」就没有共同含义了
    chunk_size        INT         NOT NULL,
    total_chunks      INT         NOT NULL,
    -- 客户端声明的采样哈希，合并后据此校验
    content_hash      VARCHAR(64) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'RECEIVING',
    -- 秒传命中：没有任何字节真的上传过
    instant           BOOLEAN     NOT NULL DEFAULT false,
    -- 秒传命中的既有物理文件。合并入库的那条要等扫描建行，所以那种情况留空
    scanned_file_id   BIGINT      REFERENCES scanned_file (id) ON DELETE SET NULL,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT ck_upload_session_status CHECK (
        status IN ('RECEIVING', 'ASSEMBLING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_upload_session_sizes CHECK (
        total_size > 0 AND chunk_size > 0 AND total_chunks > 0)
);

CREATE INDEX idx_upload_session_user ON upload_session (user_id, created_at DESC);

CREATE TABLE upload_chunk (
    session_id  BIGINT      NOT NULL REFERENCES upload_session (id) ON DELETE CASCADE,
    chunk_index INT         NOT NULL,
    size        BIGINT      NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 复合主键让「同一片重传」变成一次 ON CONFLICT，天然幂等
    PRIMARY KEY (session_id, chunk_index)
);
```

- [ ] **Step 10: 写 `upload` 模块的模型与配置**

`src/main/java/com/mymedia/upload/package-info.java`：

```java
/**
 * 分片上传。
 *
 * <p>独立成模块而不是并进 {@code library}：它有自己的状态机（会话 / 分片 / 合并）、
 * 自己的临时存储布局、自己的任务类型，而 {@code library} 是一个只管「库有哪些、
 * 谁能看」的薄模块。
 *
 * <p>依赖 {@code scan} 是为了两件事：秒传要查既有物理文件的指纹，
 * 合并落库后要触发一次增量扫描把新文件接进语义层。
 * <b>它不依赖 {@code video} 与 {@code image}</b>——上传只负责把字节放到正确的目录里，
 * 「这是一部电影还是一本漫画」由扫描链路照常判定。这是本项目
 * 「物理层共享、语义层分域」在上传这条链路上的又一次体现。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Upload",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan"})
package com.mymedia.upload;
```

`src/main/java/com/mymedia/upload/UploadProperties.java`：

```java
package com.mymedia.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * @param tempRoot  未合并的分片落在这里，独立于媒体库根目录——
 *                  半成品绝不能出现在会被扫描的目录里
 * @param chunkSize 服务端决定的分片大小，创建会话时下发给客户端
 * @param maxSize   单个文件的上界。默认 20GB：够放一部 4K 原盘，
 *                  又不至于让一次误操作把磁盘写满
 */
@ConfigurationProperties(prefix = "mymedia.upload")
record UploadProperties(
        @DefaultValue("./data/uploads") Path tempRoot,
        @DefaultValue("8388608") int chunkSize,
        @DefaultValue("21474836480") long maxSize) {
}
```

> record 形式的 `@ConfigurationProperties` 需要 `MyMediaApplication` 上的
> `@ConfigurationPropertiesScan`——**计划 05 Task 1 Step 8 已经加过了**，
> 本计划不需要再动那个文件。若执行顺序有变导致它还不在，补上那一行注解即可。

`src/main/java/com/mymedia/upload/UploadStatus.java`：

```java
package com.mymedia.upload;

/**
 * 上传会话的状态。
 *
 * <p>没有 {@code PENDING}：会话一创建就可以收分片了，
 * 多一个「已创建但还不能用」的状态只会让客户端多一次轮询。
 */
public enum UploadStatus {

    /** 正在收分片。秒传未命中的会话从这里开始。 */
    RECEIVING,
    /** 分片到齐，合并任务已入队。 */
    ASSEMBLING,
    /** 文件已落进媒体库（或秒传命中，一个字节都没传）。 */
    COMPLETED,
    /** 合并或校验失败，{@code last_error} 里有原因。 */
    FAILED
}
```

`src/main/java/com/mymedia/upload/UploadSession.java`：

```java
package com.mymedia.upload;

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
@Table(name = "upload_session")
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "target_library_id", nullable = false, updatable = false)
    private Long targetLibraryId;

    @Column(nullable = false, updatable = false)
    private String filename;

    @Column(name = "relative_path")
    private String relativePath;

    @Column(name = "total_size", nullable = false, updatable = false)
    private long totalSize;

    @Column(name = "chunk_size", nullable = false, updatable = false)
    private int chunkSize;

    @Column(name = "total_chunks", nullable = false, updatable = false)
    private int totalChunks;

    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UploadStatus status = UploadStatus.RECEIVING;

    @Column(nullable = false)
    private boolean instant;

    @Column(name = "scanned_file_id")
    private Long scannedFileId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UploadSession() {
        // JPA 要求的无参构造器
    }

    UploadSession(Long userId, Long targetLibraryId, String filename, long totalSize,
                  int chunkSize, int totalChunks, String contentHash) {
        this.userId = userId;
        this.targetLibraryId = targetLibraryId;
        this.filename = filename;
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.contentHash = contentHash;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTargetLibraryId() { return targetLibraryId; }
    public String getFilename() { return filename; }
    public String getRelativePath() { return relativePath; }
    public long getTotalSize() { return totalSize; }
    public int getChunkSize() { return chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public String getContentHash() { return contentHash; }
    public UploadStatus getStatus() { return status; }
    public boolean isInstant() { return instant; }
    public Long getScannedFileId() { return scannedFileId; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    // 注意这里<b>没有</b> markAssembling()：转 ASSEMBLING 必须是一条
    // 「判断与写入压成一句」的条件 UPDATE（Task 12 的
    // UploadSessionRepository.markAssemblingIfReceiving），否则两片同时到齐时
    // 会入队两次合并任务。留一个实体方法在这里只会诱人去用错的那条路。

    /** 秒传命中：一个字节都没传，直接指向那个既有文件。 */
    void completeInstantly(Long existingScannedFileId) {
        this.instant = true;
        this.scannedFileId = existingScannedFileId;
        this.status = UploadStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    void completeAt(String relativePath) {
        this.relativePath = relativePath;
        this.status = UploadStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    void fail(String reason) {
        this.status = UploadStatus.FAILED;
        this.lastError = reason;
        this.completedAt = Instant.now();
    }
}
```

`src/main/java/com/mymedia/upload/UploadSessionRepository.java`：

```java
package com.mymedia.upload;

import org.springframework.data.jpa.repository.JpaRepository;

interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {
}
```

- [ ] **Step 11: 写秒传判定与会话服务**

`src/main/java/com/mymedia/upload/InstantUploadResolver.java`：

```java
package com.mymedia.upload;

import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 秒传判定：这个库里是不是已经有一份一模一样的文件了。
 *
 * <p>分两步，因为 {@code scanned_file.content_hash} <b>绝大多数是 NULL</b>——
 * 计划 02 只在改名检测需要时才算它。只按哈希查会几乎全部落空，秒传形同虚设。
 *
 * <ol>
 *   <li>按 {@code content_hash} 直接查（部分索引，一次查找）</li>
 *   <li>未命中时取同库内 {@code size_bytes} 相同且哈希为空的候选，
 *       <b>现算并写回</b>，再比</li>
 * </ol>
 *
 * <p>第 2 步顺带把哈希补齐了，下次更快。代价是最多 {@value #MAX_CANDIDATES} 次
 * 2MB 读——上限是必须的：一个库里可能有几百个大小恰好相同的文件
 * （同一台设备导出的视频尤其容易撞），不设限就会让创建会话这一个请求读上几 GB。
 *
 * <p>取舍与边界写在 ADR-007。
 */
@Component
class InstantUploadResolver {

    /** 同尺寸候选的现算上限。 */
    static final int MAX_CANDIDATES = 8;

    private static final Logger log = LoggerFactory.getLogger(InstantUploadResolver.class);

    private final ScannedFileHashService hashService;

    InstantUploadResolver(ScannedFileHashService hashService) {
        this.hashService = hashService;
    }

    Optional<ScannedFile> resolve(Long libraryId, long sizeBytes, String contentHash) {
        Optional<ScannedFile> direct = hashService.findActiveByContentHash(libraryId, contentHash);
        if (direct.isPresent()) {
            log.debug("秒传命中（已有哈希）: libraryId={} hash={}", libraryId, contentHash);
            return direct;
        }

        for (ScannedFile candidate :
                hashService.findActiveBySizeWithoutHash(libraryId, sizeBytes, MAX_CANDIDATES)) {

            Optional<String> computed = hashService.computeAndStoreHash(candidate.getId());
            if (computed.filter(contentHash::equals).isPresent()) {
                log.debug("秒传命中（现算候选）: libraryId={} path={}",
                        libraryId, candidate.getRelativePath());
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
```

`src/main/java/com/mymedia/upload/UploadSessionService.java`：

```java
package com.mymedia.upload;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 上传会话的创建与查询。
 *
 * <p><b>分片大小由服务端决定并下发</b>，客户端不许自选：分片边界一旦由两边
 * 各自计算，「第 N 片」就没有共同含义了，断点续传会把文件拼错。
 */
@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    private final UploadSessionRepository repository;
    private final InstantUploadResolver instantResolver;
    private final LibraryAccessService accessService;
    private final UploadProperties properties;

    UploadSessionService(UploadSessionRepository repository,
                         InstantUploadResolver instantResolver,
                         LibraryAccessService accessService,
                         UploadProperties properties) {
        this.repository = repository;
        this.instantResolver = instantResolver;
        this.accessService = accessService;
        this.properties = properties;
    }

    /**
     * 建一个会话，顺带先试一次秒传。
     *
     * @param contentHash 客户端算好的采样哈希。<b>是必填的</b>——没有它就既没法秒传，
     *                    也没法在合并后校验拼出来的东西对不对。算它只需要读文件首尾
     *                    各 1MB，浏览器用 {@code File.slice} + WebCrypto 就能做到。
     */
    @Transactional
    public UploadSession create(Long userId, String filename, long totalSize,
                                String contentHash, Long libraryId) {
        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }
        if (totalSize <= 0 || totalSize > properties.maxSize()) {
            throw new IllegalArgumentException(
                    "文件大小超出允许范围（上限 " + properties.maxSize() + " 字节）: " + totalSize);
        }

        String safeName = SafeFileName.of(filename);
        int chunkSize = properties.chunkSize();
        // 向上取整；totalSize 已保证 > 0，所以至少一片
        int totalChunks = (int) ((totalSize + chunkSize - 1) / chunkSize);

        UploadSession session = new UploadSession(userId, libraryId, safeName, totalSize,
                chunkSize, totalChunks, contentHash);

        Optional<ScannedFile> existing = instantResolver.resolve(libraryId, totalSize, contentHash);
        if (existing.isPresent()) {
            // 已经有一份一模一样的了。再存一份物理副本不是「更安全」，只是浪费磁盘——
            // 这正是内容寻址的意义
            session.completeInstantly(existing.get().getId());
            log.info("秒传完成 user={} library={} file={} -> 既有文件 id={}",
                    userId, libraryId, safeName, existing.get().getId());
        }
        return repository.save(session);
    }

    /** 查会话。<b>别人的会话一律 404</b>，不确认它是否存在。 */
    @Transactional(readOnly = true)
    public UploadSession get(Long userId, Long sessionId) {
        return repository.findById(sessionId)
                .filter(session -> session.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("找不到上传会话 id=" + sessionId));
    }
}
```

- [ ] **Step 12: 写端点与配置默认值**

`src/main/java/com/mymedia/upload/web/UploadDto.java`：

```java
package com.mymedia.upload.web;

import com.mymedia.upload.UploadSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

final class UploadDto {

    private UploadDto() {
    }

    /**
     * @param contentHash 64 位小写十六进制。<b>格式在这里就卡死</b>——
     *                    它会被拼进 SQL 参数与日志，早失败比晚失败好
     */
    record CreateRequest(
            @NotBlank String filename,
            @Positive long totalSize,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash,
            @NotNull Long targetLibraryId) {
    }

    /**
     * @param receivedChunks 已经收到的分片下标，升序。客户端据此只补传缺的那些，
     *                       这就是断点续传的全部机制
     */
    record Response(
            Long id,
            String status,
            boolean instant,
            String filename,
            long totalSize,
            int chunkSize,
            int totalChunks,
            List<Integer> receivedChunks,
            Long scannedFileId,
            String relativePath,
            String lastError,
            Instant completedAt) {

        static Response from(UploadSession session, List<Integer> receivedChunks) {
            return new Response(
                    session.getId(),
                    session.getStatus().name(),
                    session.isInstant(),
                    session.getFilename(),
                    session.getTotalSize(),
                    session.getChunkSize(),
                    session.getTotalChunks(),
                    receivedChunks,
                    session.getScannedFileId(),
                    session.getRelativePath(),
                    session.getLastError(),
                    session.getCompletedAt());
        }
    }
}
```

`src/main/java/com/mymedia/upload/web/UploadController.java`：

```java
package com.mymedia.upload.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.upload.UploadSessionService;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/upload")
class UploadController {

    private final UploadSessionService sessionService;
    private final UserQueryService userQueryService;

    UploadController(UploadSessionService sessionService, UserQueryService userQueryService) {
        this.sessionService = sessionService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    UploadDto.Response create(@AuthenticationPrincipal UserDetails principal,
                              @Valid @RequestBody UploadDto.CreateRequest request) {
        return UploadDto.Response.from(
                sessionService.create(currentUserId(principal), request.filename(),
                        request.totalSize(), request.contentHash(), request.targetLibraryId()),
                List.of());
    }

    /** 断点续传的入口：客户端问「我传到哪儿了」。Task 11 会把已收分片填进来。 */
    @GetMapping("/sessions/{id}")
    UploadDto.Response get(@AuthenticationPrincipal UserDetails principal,
                           @PathVariable Long id) {
        return UploadDto.Response.from(
                sessionService.get(currentUserId(principal), id), List.of());
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

在 `src/main/resources/application.yml` 里补上本计划引入的两组配置
（放在既有 `mymedia:` 节点下）：

```yaml
mymedia:
  share:
    # 留空则每次启动随机生成，重启后带密码的分享链接需要访客重新输一次密码。
    # 生产部署应当配一个固定值（任意长随机串）。
    secret: ""
    ticket-ttl: PT12H
  upload:
    # 未合并的分片落在这里，独立于媒体库根目录——半成品绝不能出现在会被扫描的目录里
    temp-root: ./data/uploads
    chunk-size: 8388608      # 8MB
    max-size: 21474836480    # 20GB
```

- [ ] **Step 13: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='SafeFileNameTest,SampledHashTest,InstantUploadResolverTest,UploadSessionServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources src/main/java/com/mymedia src/test/java/com/mymedia
git commit -m "feat: SampledHash 提升到 shared，新增上传会话与秒传

SampledHash 是纯算法不带状态，与 NaturalSortKey/MaterializedPath 并列——
'复用算法，不复用模型'。算法一个字节没动，原有测试原样通过就是证据。

它的形状顺带让秒传在客户端也便宜：浏览器 File.slice 读首尾各 1MB 就能算出
一致的指纹，不必先读完 20GB 再问服务端'你有没有'。

秒传分两步：content_hash 绝大多数是 NULL（计划 02 只在改名检测时才算），
所以未命中时取同尺寸候选现算并写回，上限 8 个。算过的哈希会留下。

SafeFileName 是安全边界：路径穿越、控制字符、Windows 保留字符、
会被静默吃掉的结尾点和空格，每一条都有单元测试。"
```

Expected: `EXIT=0`，`SafeFileNameTest` 9 个、`InstantUploadResolverTest` 6 个、
`UploadSessionServiceTest` 9 个用例通过，`SampledHashTest` 原有 8 个仍然通过。

---

## Task 11: 分片上传与断点续传

**Files:**
- Create: `src/main/java/com/mymedia/upload/UploadStorage.java`
- Create: `src/main/java/com/mymedia/upload/UploadChunkStore.java`
- Modify: `src/main/java/com/mymedia/upload/UploadSessionService.java`（新增 `receiveChunk` 与 `receivedChunks`）
- Modify: `src/main/java/com/mymedia/upload/web/UploadController.java`（新增分片端点，`GET` 带回已收清单）
- Test: `src/test/java/com/mymedia/upload/UploadChunkTest.java`

**Interfaces:**
- Consumes: `UploadSession`、`UploadSessionService.get`、`UploadProperties`、`UploadStatus`（Task 10）
- Produces:
  - `class UploadStorage`（package-private）
    - `long writeChunk(Long sessionId, int index, InputStream body, long limit) throws IOException`
    - `Path chunkPath(Long sessionId, int index)`
    - `Path sessionDir(Long sessionId)`
    - `void deleteSession(Long sessionId) throws IOException`
  - `class UploadChunkStore`（package-private）
    - `void record(Long sessionId, int index, long size)`
    - `List<Integer> receivedIndexes(Long sessionId)`
    - `int count(Long sessionId)`
  - `UploadSessionService` 新增：
    - `public void receiveChunk(Long userId, Long sessionId, int index, InputStream body) throws IOException`
    - `public List<Integer> receivedChunks(Long userId, Long sessionId)`
  - `PUT /api/upload/sessions/{id}/chunks/{index}`（`application/octet-stream`）

### 为什么走原始请求体而不是 multipart

Boot 4 的 multipart 属性没有改名（仍是 `spring.servlet.multipart.*`，
声明在 `spring-boot-servlet-4.1.0.jar`，经 `starter-webmvc → spring-boot-webmvc →
spring-boot-servlet` 传递引入，`mvn dependency:tree` 确认为 `compile` 作用域，
**不需要新增依赖**）。但它的默认值对分片上传是致命的：

| 属性 | 默认值 |
|---|---|
| `spring.servlet.multipart.max-file-size` | **1MB** |
| `spring.servlet.multipart.max-request-size` | **10MB** |

**分片体直接是 `application/octet-stream`，从 `HttpServletRequest.getInputStream()`
流式落盘**，于是：

- 不受那两个限制约束，不需要为了上传去调全局配置（调了会顺带放宽所有别的表单端点）。
- 不经过 multipart 解析器，**分片内容一个字节都不进内存**。
- 请求体就是分片本身，没有边界解析的开销，也没有临时文件的二次落盘。

元信息全在 URL 里（`{id}` 与 `{index}`），不需要表单字段——
这也是 multipart 在这里唯一能提供的东西。

### 写文件的这段时间里绝不持有数据库事务

一个 8MB 分片在慢网络上可能写好几秒。若 `receiveChunk` 整个方法挂着
`@Transactional`，连接池会被一群正在传输的请求占满，整个应用其它部分全部卡住。

所以 `receiveChunk` **本身不是事务方法**：读会话是一次短事务，落盘不在事务里，
记录分片又是一次短事务。这是「事务边界要贴着数据库操作，而不是贴着业务动作」
的一个具体例子。

### 分片大小要当场核对

客户端可能算错边界、可能中途断线只送了一半、也可能恶意送一个无限长的体。
所以 `writeChunk` **最多读 `limit + 1` 字节**：

- 读到的字节数与期望不符 → 删掉这个半成品，返回 400。
- 多读出来的那 1 个字节是探针：能读到它就说明客户端超发了。

不设上限的话，一个撒谎的 `Content-Length` 就能把磁盘写满。

### 已收分片清单就是断点续传的全部机制

`GET /api/upload/sessions/{id}` 返回 `receivedChunks: [0, 1, 3]`，
客户端看一眼就知道要补第 2 片。**不需要服务端记住"传到第几个字节"**——
分片是原子的，要么整片到了要么没到，这正是分片的意义。

同一片重传是幂等的：文件被原子替换，`upload_chunk` 行走 `ON CONFLICT DO UPDATE`。

### 路径里没有任何用户可控的字符串

分片落在 `{tempRoot}/{sessionId}/{index}.part`，两个都是数字。
`filename` 净化后只在 Task 12 的合并阶段才用到，**收分片这一路根本不碰它**——
少一个能出错的地方。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/upload/UploadChunkTest.java`：

```java
package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片大小压到 16 字节，这样一个 40 字节的"文件"就是 3 片，
 * 用几十个字节把整套边界跑一遍——不需要真的传 8MB。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=false",
        "mymedia.upload.chunk-size=16"
})
class UploadChunkTest extends AbstractIntegrationTest {

    private static final byte[] WHOLE = "0123456789abcdefGHIJKLMNOPQRSTUVwxyz!!!!".getBytes();
    private static final String FAKE_HASH = "b".repeat(64);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UploadSessionService sessionService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private String username;
    private Long userId;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        MediaLibrary library = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());

        sessionId = sessionService.create(userId, "movie.mkv", WHOLE.length, FAKE_HASH,
                library.getId()).getId();
    }

    private byte[] chunk(int index) {
        int from = index * 16;
        int to = Math.min(from + 16, WHOLE.length);
        byte[] slice = new byte[to - from];
        System.arraycopy(WHOLE, from, slice, 0, slice.length);
        return slice;
    }

    private org.springframework.test.web.servlet.ResultActions send(int index, byte[] body)
            throws Exception {
        return mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, index)
                .with(httpBasic(username, "pw"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body));
    }

    @Test
    void aFortyByteFileIsThreeChunksWithAShortLastOne() {
        UploadSession session = sessionService.get(userId, sessionId);

        assertThat(session.getChunkSize()).isEqualTo(16);
        assertThat(session.getTotalChunks()).isEqualTo(3);
        assertThat(chunk(2)).hasSize(8);
    }

    @Test
    void chunksAreAcceptedAndShowUpInTheReceivedList() throws Exception {
        send(0, chunk(0)).andExpect(status().isNoContent());
        send(1, chunk(1)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(0, 1)));
    }

    @Test
    void chunksMayArriveOutOfOrder() throws Exception {
        send(2, chunk(2)).andExpect(status().isNoContent());
        send(0, chunk(0)).andExpect(status().isNoContent());

        // 清单永远是升序的，与到达顺序无关
        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(0, 2)));
    }

    @Test
    void resendingTheSameChunkIsIdempotent() throws Exception {
        send(1, chunk(1)).andExpect(status().isNoContent());
        send(1, chunk(1)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void theGapInTheListIsExactlyWhatTheClientNeedsToResend() throws Exception {
        send(0, chunk(0)).andExpect(status().isNoContent());
        send(2, chunk(2)).andExpect(status().isNoContent());

        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 2);

        send(1, chunk(1)).andExpect(status().isNoContent());

        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 1, 2);
    }

    @Test
    void aChunkThatIsTooShortIsRejectedAndLeavesNothingBehind() throws Exception {
        send(0, "short".getBytes()).andExpect(status().isBadRequest());

        assertThat(sessionService.receivedChunks(userId, sessionId)).isEmpty();
    }

    @Test
    void aChunkThatIsTooLongIsRejected() throws Exception {
        send(0, "way too many bytes for one chunk".getBytes())
                .andExpect(status().isBadRequest());

        assertThat(sessionService.receivedChunks(userId, sessionId)).isEmpty();
    }

    @Test
    void anIndexOutsideTheDeclaredRangeIsRejected() throws Exception {
        send(3, chunk(0)).andExpect(status().isBadRequest());
        send(-1, chunk(0)).andExpect(status().isBadRequest());
    }

    @Test
    void someoneElsesSessionIsNotFound() throws Exception {
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, 0)
                        .with(httpBasic(stranger, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk(0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSessionThatIsNoLongerReceivingRefusesChunks() throws Exception {
        // 秒传命中后客户端还傻传，或者合并已经开始了——两种情况都走这条路。
        // 直接 UPDATE 而不是去戳实体：这里要断言的是服务对 status 的反应
        jdbc.update("UPDATE upload_session SET status = 'COMPLETED' WHERE id = ?", sessionId);

        send(0, chunk(0)).andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=UploadChunkTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`UploadSessionService.receivedChunks` 不存在。

- [ ] **Step 3: 写临时存储布局**

`src/main/java/com/mymedia/upload/UploadStorage.java`：

```java
package com.mymedia.upload;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * 未合并分片的临时存储。
 *
 * <p>布局 {@code {tempRoot}/{sessionId}/{index}.part}。<b>两级都是数字</b>——
 * 用户给的 {@code filename} 在收分片这一路根本不参与路径构造，
 * 少一个能出错的地方。它只在 Task 12 的合并阶段才被用到，那时已经过
 * {@code SafeFileName} 净化。
 *
 * <p>临时目录独立于媒体库根目录：<b>半成品绝不能出现在会被扫描的目录里</b>，
 * 否则扫描会把一个只传了一半的文件当成新媒体入库。
 */
@Component
class UploadStorage {

    private final Path tempRoot;

    UploadStorage(UploadProperties properties) {
        this.tempRoot = properties.tempRoot().toAbsolutePath().normalize();
    }

    Path sessionDir(Long sessionId) {
        return tempRoot.resolve(String.valueOf(sessionId));
    }

    Path chunkPath(Long sessionId, int index) {
        return sessionDir(sessionId).resolve(index + ".part");
    }

    /**
     * 把请求体落成一个分片，返回实际写入的字节数。
     *
     * <p><b>最多读 {@code limit + 1} 字节</b>：多出来的那一个字节是探针，
     * 能读到它就说明客户端超发了。不设上限的话，一个撒谎的 {@code Content-Length}
     * 就能把磁盘写满。调用方负责比对返回值与期望值。
     *
     * <p>先写 {@code .tmp} 再原子改名：中途断线不会留下一个"看起来完整"的分片。
     */
    long writeChunk(Long sessionId, int index, InputStream body, long limit) throws IOException {
        Path dir = sessionDir(sessionId);
        Files.createDirectories(dir);
        Path staging = dir.resolve(index + ".part.tmp");

        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(staging)) {
            while (written <= limit) {
                int read = body.read(buffer, 0, (int) Math.min(buffer.length, limit + 1 - written));
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                written += read;
            }
        }

        if (written != limit) {
            Files.deleteIfExists(staging);
            return written;
        }
        Files.move(staging, chunkPath(sessionId, index),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return written;
    }

    /** 合并完成或会话作废时清掉整个目录。 */
    void deleteSession(Long sessionId) throws IOException {
        Path dir = sessionDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
```

- [ ] **Step 4: 写分片记录表的读写**

`src/main/java/com/mymedia/upload/UploadChunkStore.java`：

```java
package com.mymedia.upload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code upload_chunk} 的读写。
 *
 * <p>用 {@code JdbcTemplate} 而不是 JPA：它只有三个动作，其中「记录一片」
 * 是一条带 {@code ON CONFLICT} 的 upsert——那正是 JPA 表达起来最别扭、
 * 而 SQL 表达起来最自然的一类操作。与计划 04 的
 * {@code ImageLibraryRecalculator} 是同一条取舍。
 */
@Component
class UploadChunkStore {

    private final JdbcTemplate jdbc;

    UploadChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同一片重传是幂等的：主键冲突时更新大小与时间，不报错。 */
    @Transactional
    void record(Long sessionId, int index, long size) {
        jdbc.update("""
                INSERT INTO upload_chunk (session_id, chunk_index, size)
                VALUES (?, ?, ?)
                ON CONFLICT (session_id, chunk_index)
                DO UPDATE SET size = EXCLUDED.size, received_at = now()
                """, sessionId, index, size);
    }

    @Transactional(readOnly = true)
    List<Integer> receivedIndexes(Long sessionId) {
        return jdbc.queryForList(
                "SELECT chunk_index FROM upload_chunk WHERE session_id = ? ORDER BY chunk_index",
                Integer.class, sessionId);
    }

    @Transactional(readOnly = true)
    int count(Long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM upload_chunk WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }
}
```

- [ ] **Step 5: 给会话服务加收分片**

在 `src/main/java/com/mymedia/upload/UploadSessionService.java` 的构造参数里加入
`UploadStorage storage` 与 `UploadChunkStore chunkStore`（连同同名字段），并追加：

```java
    /**
     * 收下一个分片。
     *
     * <p><b>本方法刻意不是 {@code @Transactional} 的。</b>一个 8MB 分片在慢网络上
     * 可能写好几秒，若整个方法挂着事务，连接池会被一群正在传输的请求占满，
     * 整个应用的其它部分全部卡住。读会话是一次短事务，落盘不在事务里，
     * 记录分片又是一次短事务——事务边界贴着数据库操作，不贴着业务动作。
     *
     * @throws IllegalArgumentException 下标越界，或分片大小与声明不符（→ 400）
     * @throws ResponseStatusException  会话已经不在收片状态（→ 409）。用它是因为
     *         「状态不对」没有合适的领域异常，而 {@code shared} 的
     *         {@code GlobalExceptionHandler} 不可能认识每个模块自己的异常类型
     */
    public void receiveChunk(Long userId, Long sessionId, int index, InputStream body)
            throws IOException {

        UploadSession session = get(userId, sessionId);

        if (session.getStatus() != UploadStatus.RECEIVING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "会话当前状态不接受分片: " + session.getStatus());
        }
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new IllegalArgumentException("分片下标越界: " + index
                    + "，本次上传共 " + session.getTotalChunks() + " 片");
        }

        long expected = expectedChunkSize(session, index);
        long written = storage.writeChunk(sessionId, index, body, expected);
        if (written != expected) {
            throw new IllegalArgumentException(
                    "分片大小不符：期望 " + expected + " 字节，实际 " + written);
        }
        chunkStore.record(sessionId, index, written);
    }

    /** 最后一片通常是短的；其余都是整片。 */
    private static long expectedChunkSize(UploadSession session, int index) {
        long offset = (long) index * session.getChunkSize();
        return Math.min(session.getChunkSize(), session.getTotalSize() - offset);
    }

    /**
     * 已经收到的分片下标，升序。
     *
     * <p><b>断点续传的全部机制就是这一个列表</b>：客户端看一眼就知道要补哪几片。
     * 不需要服务端记住「传到第几个字节」——分片是原子的，要么整片到了要么没到。
     */
    @Transactional(readOnly = true)
    public List<Integer> receivedChunks(Long userId, Long sessionId) {
        return chunkStore.receivedIndexes(get(userId, sessionId).getId());
    }
```

需要新增 import：`java.io.IOException`、`java.io.InputStream`、`java.util.List`、
`org.springframework.http.HttpStatus`、`org.springframework.web.server.ResponseStatusException`。

- [ ] **Step 6: 写分片端点**

在 `src/main/java/com/mymedia/upload/web/UploadController.java` 里，把 `GET` 改成带回
已收清单，并新增 `PUT`：

```java
    /** 断点续传的入口：客户端问「我传到哪儿了」，拿到已收清单后只补缺的那几片。 */
    @GetMapping("/sessions/{id}")
    UploadDto.Response get(@AuthenticationPrincipal UserDetails principal,
                           @PathVariable Long id) {
        Long userId = currentUserId(principal);
        return UploadDto.Response.from(
                sessionService.get(userId, id),
                sessionService.receivedChunks(userId, id));
    }

    /**
     * 收一个分片。
     *
     * <p><b>请求体就是分片本身</b>（{@code application/octet-stream}），
     * 不走 multipart：Boot 的 multipart 默认上限是 1MB/10MB，而且解析器会把内容
     * 先落成临时文件再交给我们，等于白写一遍磁盘。这里从
     * {@code HttpServletRequest.getInputStream()} 直接流式落盘，
     * <b>分片内容一个字节都不进内存</b>。
     *
     * <p>元信息全在 URL 里，multipart 能提供的表单字段这里根本用不上。
     */
    @PutMapping(path = "/sessions/{id}/chunks/{index}",
                consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void putChunk(@AuthenticationPrincipal UserDetails principal,
                  @PathVariable Long id,
                  @PathVariable int index,
                  HttpServletRequest request) throws IOException {
        sessionService.receiveChunk(currentUserId(principal), id, index, request.getInputStream());
    }
```

需要新增 import：`jakarta.servlet.http.HttpServletRequest`、`java.io.IOException`、
`org.springframework.http.MediaType`、`org.springframework.web.bind.annotation.PutMapping`。

- [ ] **Step 7: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='UploadChunkTest,UploadSessionServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/upload src/test/java/com/mymedia/upload
git commit -m "feat: 分片上传与断点续传

分片体走 application/octet-stream 而不是 multipart：Boot 的 multipart 默认上限是
1MB/10MB，而且会先落一遍临时文件。直接从 getInputStream 流式落盘，
分片内容一个字节都不进内存。

receiveChunk 刻意不是 @Transactional 的：一个分片写几秒，挂着事务会把连接池
占满。事务边界贴着数据库操作，不贴着业务动作。

writeChunk 最多读 limit+1 字节，多出来那一个是探针——不设上限的话
一个撒谎的 Content-Length 就能把磁盘写满。

已收分片清单就是断点续传的全部机制，不需要记'传到第几个字节'。"
```

Expected: `EXIT=0`，`UploadChunkTest` 10 个用例通过。

> **本任务结束时全部分片到齐的会话仍停在 `RECEIVING`，这是对的**：
> 触发合并是 Task 12 的事。Task 12 会加上「最后一片落地后转 `ASSEMBLING`
> 并入队 `UPLOAD_ASSEMBLE`」，并把这一条补进测试。

---

## Task 12: 合并、校验、入库与触发扫描

**Files:**
- Modify: `src/main/java/com/mymedia/upload/UploadStorage.java`（新增 `assembleInto`）
- Modify: `src/main/java/com/mymedia/upload/UploadSessionRepository.java`（新增原子状态跃迁）
- Modify: `src/main/java/com/mymedia/upload/UploadSessionService.java`（收到最后一片后入队；新增两个状态方法）
- Create: `src/main/java/com/mymedia/upload/UploadAssembler.java`
- Create: `src/main/java/com/mymedia/upload/UploadAssembleJobHandler.java`
- Test: `src/test/java/com/mymedia/upload/UploadEndToEndTest.java`

**Interfaces:**
- Consumes: `UploadStorage`、`UploadChunkStore`、`UploadSession`（Task 10/11）、`SampledHash`（Task 10）、`JobQueue`、`JobHandler`、`Job`（计划 01）、`ScanTrigger`（计划 02）、`LibraryService`（计划 01）
- Produces:
  - `UploadStorage.assembleInto(Long sessionId, int totalChunks, Path target)` → `long`（写出的字节数）
  - `UploadSessionRepository.markAssemblingIfReceiving(Long id)` → `int`
  - `UploadSessionService` 新增：`void markCompleted(Long sessionId, String relativePath)`、`void markFailed(Long sessionId, String reason)`（均为 package-private）
  - `class UploadAssembler`（package-private）— `void assemble(Long sessionId) throws IOException`
  - `class UploadAssembleJobHandler`（package-private）— 任务类型常量 `UPLOAD_ASSEMBLE`

### 合并为什么是一个后台任务而不是最后一片的同步动作

合并一个 20GB 的文件要读写 40GB。挂在「最后一片」那个 HTTP 请求上意味着：
客户端要等几分钟才拿到 204，中间断线就不知道成没成功，重试还会再合一遍。

排成任务之后，`job` 表白送三样东西：**重试**（IO 抖动自动再来一次）、
**可观测**（`GET /api/upload/sessions/{id}` 看得到 `ASSEMBLING`）、
**崩溃恢复**（租约过期后被别的 worker 捡起来）。这正是 ADR-003
「用数据库任务表替代消息队列」当初想要的收益，这里是它的第五个受益者。

### 状态跃迁必须是原子的，否则会合并两次

两片几乎同时到达时，两个线程都会看到 `count == totalChunks`。
所以「转 `ASSEMBLING`」写成一条**条件 UPDATE**：

```sql
UPDATE upload_session SET status = 'ASSEMBLING' WHERE id = ? AND status = 'RECEIVING'
```

只有拿到 `1` 的那个线程去入队。**这不是"再加一把锁"，而是把判断和写入压成一条语句**——
和计划 05 的 `UPDATE … WHERE cover_asset_id IS NULL` 是同一个手法，
在这个项目里出现第三次了。

即便两个都入队也不会出事：`dedup_key = upload-assemble:{id}` 上的唯一约束
会让第二次入队返回第一次的任务 id。**两道防线，各自都能独立成立。**

### 失败分两种，只有一种值得重试

| 失败 | 处理 | 为什么 |
|---|---|---|
| 分片缺失、大小对不上、哈希不符 | 标 `FAILED`，任务算**成功** | 再合一百遍结果也一样，重试只是浪费。用户看到 `lastError` 后重新上传 |
| 读写异常（磁盘满、目标目录不可写） | **不改状态，抛出去** | 任务表会按指数退避重试，可能下一次就好了 |

「处理器完成了自己该做的事」和「上传成功了」是两件事——
把前者当成后者会让一个哈希不符的上传永远重试到 `max_attempts`。

### 校验能证明什么、不能证明什么（写进 ADR-007）

合并后重算 `SampledHash` 并与客户端声明的比对，能抓住的是：
**传了另一个文件**、**丢了整块首尾**、**总长度不对**。

抓不住的是**中段某几个字节被改了**——采样哈希本来就不读中段。
逐字节完整性由三层承担：TCP 校验和、TLS 的 AEAD、以及每一片
`written != expected` 的当场核对。**在自托管媒体库这个场景里这已经够了**，
而换成全量 SHA-256 意味着客户端在按下上传之前要先读完 20GB。
这个取舍要照实写进 ADR-007，不能含糊成"做了完整性校验"。

### 重名不覆盖，追加 `(2)`

目标目录里已经有 `movie.mkv` 时落成 `movie (2).mkv`。
**绝不覆盖**：那是别人的文件，而扫描是靠路径认文件的，覆盖等于悄悄换掉一部片子
的内容而目录树纹丝不动。

- [ ] **Step 1: 写会失败的端到端测试**

`src/test/java/com/mymedia/upload/UploadEndToEndTest.java`：

```java
package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SampledHash;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片 → 断点续传 → 合并 → 校验 → 落库 → 触发扫描，一条链路走到底。
 *
 * <p>分片大小压到 16 字节：整条链路的每一个边界都能用几十个字节跑到，
 * 不需要真的搬 8MB。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=false",
        "mymedia.upload.chunk-size=16",
        // @TestPropertySource 的值必须是编译期常量，拿不到 @TempDir 生成的路径，
        // 所以临时目录用一个固定的相对路径。target/ 本来就会被 mvn clean 清掉
        "mymedia.upload.temp-root=./target/test-uploads"
})
class UploadEndToEndTest extends AbstractIntegrationTest {

    private static final byte[] WHOLE = "0123456789abcdefGHIJKLMNOPQRSTUVwxyz!!!!".getBytes();

    private static final Path UPLOAD_TEMP = Path.of("./target/test-uploads");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UploadSessionService sessionService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path scratchDir;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private String username;
    private Long userId;
    private String wholeHash;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(UPLOAD_TEMP);

        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());

        // 客户端会算的那个哈希：这里用一个临时文件算出同样的值。
        // 它必须落在媒体库<b>之外</b>，否则会被扫描当成一个新文件
        Path scratch = Files.write(scratchDir.resolve("scratch.bin"), WHOLE);
        wholeHash = SampledHash.of(scratch, WHOLE.length);
    }

    private byte[] chunk(int index) {
        int from = index * 16;
        int to = Math.min(from + 16, WHOLE.length);
        byte[] slice = new byte[to - from];
        System.arraycopy(WHOLE, from, slice, 0, slice.length);
        return slice;
    }

    private void send(Long sessionId, int index, byte[] body) throws Exception {
        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, index)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private Long newSession(String filename) {
        return sessionService.create(userId, filename, WHOLE.length, wholeHash, library.getId())
                .getId();
    }

    @Test
    void aResumedUploadEndsUpInTheLibraryAndGetsScanned() throws Exception {
        Long sessionId = newSession("movie.mkv");

        // 第一次只传两片就"断线"了
        send(sessionId, 0, chunk(0));
        send(sessionId, 2, chunk(2));
        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 2);
        assertThat(sessionService.get(userId, sessionId).getStatus())
                .isEqualTo(UploadStatus.RECEIVING);

        // 续传缺的那一片，会话立刻转入合并
        send(sessionId, 1, chunk(1));
        assertThat(sessionService.get(userId, sessionId).getStatus())
                .isEqualTo(UploadStatus.ASSEMBLING);

        jobPoller.pollOnce();   // UPLOAD_ASSEMBLE

        UploadSession done = sessionService.get(userId, sessionId);
        assertThat(done.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(done.getRelativePath()).isEqualTo("movie.mkv");
        assertThat(libraryRoot.resolve("movie.mkv")).exists();
        assertThat(Files.readAllBytes(libraryRoot.resolve("movie.mkv"))).isEqualTo(WHOLE);

        // 临时目录清干净了，半成品不会留在磁盘上
        assertThat(Files.exists(UPLOAD_TEMP.resolve(String.valueOf(sessionId)))).isFalse();

        jobPoller.pollOnce();   // 合并时排出的 LIBRARY_SCAN

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM scanned_file WHERE library_id = ? AND relative_path = 'movie.mkv'
                """, Integer.class, library.getId())).isEqualTo(1);
    }

    @Test
    void aSecondUploadOfTheSameBytesIsInstantAndCopiesNothing() throws Exception {
        Long first = newSession("movie.mkv");
        send(first, 0, chunk(0));
        send(first, 1, chunk(1));
        send(first, 2, chunk(2));
        jobPoller.pollOnce();
        jobPoller.pollOnce();   // 让扫描把 scanned_file 建出来

        UploadSession second = sessionService.create(userId, "movie-copy.mkv",
                WHOLE.length, wholeHash, library.getId());

        assertThat(second.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(second.isInstant()).isTrue();
        // 秒传不产生第二份物理副本——这正是内容寻址的意义
        assertThat(Files.exists(libraryRoot.resolve("movie-copy.mkv"))).isFalse();
    }

    @Test
    void aNameCollisionGetsASuffixInsteadOfOverwriting() throws Exception {
        Files.write(libraryRoot.resolve("movie.mkv"), "someone else's file".getBytes());

        Long sessionId = newSession("movie.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, chunk(2));
        jobPoller.pollOnce();

        assertThat(sessionService.get(userId, sessionId).getRelativePath())
                .isEqualTo("movie (2).mkv");
        // 别人的文件一个字节都没动
        assertThat(Files.readString(libraryRoot.resolve("movie.mkv")))
                .isEqualTo("someone else's file");
    }

    @Test
    void aHashMismatchFailsTheSessionAndDoesNotRetryForever() throws Exception {
        // 声明的哈希是对的，但最后一片送的是别的内容
        Long sessionId = newSession("tampered.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, "XXXXXXXX".getBytes());   // 长度对，内容不对

        jobPoller.pollOnce();

        UploadSession failed = sessionService.get(userId, sessionId);
        assertThat(failed.getStatus()).isEqualTo(UploadStatus.FAILED);
        assertThat(failed.getLastError()).contains("哈希");
        assertThat(Files.exists(libraryRoot.resolve("tampered.mkv"))).isFalse();

        // 任务本身算成功——再合一百遍结果也一样，重试只是浪费
        assertThat(jdbc.queryForObject("""
                SELECT status FROM job WHERE type = 'UPLOAD_ASSEMBLE'
                  AND payload->>'sessionId' = ?
                """, String.class, String.valueOf(sessionId))).isEqualTo("SUCCEEDED");
    }

    @Test
    void theAssembleJobIsEnqueuedExactlyOnce() throws Exception {
        Long sessionId = newSession("once.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, chunk(2));
        // 重传最后一片：count 仍然等于 totalChunks，但状态已经不是 RECEIVING 了
        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, 2)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk(2)))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM job WHERE type = 'UPLOAD_ASSEMBLE'
                  AND payload->>'sessionId' = ?
                """, Integer.class, String.valueOf(sessionId))).isEqualTo(1);
    }
}
```

> 两个 `@TempDir` 各有各的用处，别合并：`libraryRoot` 是媒体库根目录，
> 会被扫描；`scratchDir` 只是用来算哈希的草稿纸，**必须在媒体库之外**，
> 否则那个 `scratch.bin` 会被扫描当成一个新文件，把 `scanned_file` 的断言弄脏。
> 上传临时目录则用固定的 `./target/test-uploads`（`@TestPropertySource`
> 的值必须是编译期常量，塞不进 `@TempDir` 的路径）。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=UploadEndToEndTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

Expected: 编译失败，`UploadStatus.ASSEMBLING` 的跃迁还没人触发（或 `assembleInto` 不存在）。

- [ ] **Step 3: 给存储加合并**

在 `src/main/java/com/mymedia/upload/UploadStorage.java` 追加
（新增 import：`java.nio.channels.FileChannel`、`java.nio.file.StandardOpenOption`）：

```java
    /**
     * 把 0..{@code totalChunks-1} 号分片按序拼成一个文件，返回总字节数。
     *
     * <p>用 {@code FileChannel.transferTo} 逐片搬运：数据在内核态直接从一个文件
     * 走到另一个，不经过 JVM 堆。与视频流式传输用的是同一个原语。
     *
     * <p>缺片直接抛 {@link IOException}——调用方会把它翻成一次可重试的失败，
     * 而不是拼出一个中间少一块的文件。
     */
    long assembleInto(Long sessionId, int totalChunks, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        long total = 0;

        try (FileChannel out = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {

            for (int index = 0; index < totalChunks; index++) {
                Path chunk = chunkPath(sessionId, index);
                if (!Files.exists(chunk)) {
                    throw new IOException("缺少分片 " + index + "（会话 " + sessionId + "）");
                }
                try (FileChannel in = FileChannel.open(chunk, StandardOpenOption.READ)) {
                    long size = in.size();
                    long moved = 0;
                    while (moved < size) {
                        long step = in.transferTo(moved, size - moved, out);
                        if (step <= 0) {
                            throw new IOException("分片 " + index + " 读取中断");
                        }
                        moved += step;
                    }
                    total += size;
                }
            }
        }
        return total;
    }
```

- [ ] **Step 4: 加原子状态跃迁与两个状态方法**

`src/main/java/com/mymedia/upload/UploadSessionRepository.java` 改为：

```java
package com.mymedia.upload;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    /**
     * 把会话从「收片中」推进到「合并中」，<b>只有一个调用者能拿到 1</b>。
     *
     * <p>两片几乎同时到达时两个线程都会看到「片齐了」。判断与写入压成一条
     * 条件 UPDATE，竞争就由数据库解决——和计划 05 的
     * {@code UPDATE … WHERE cover_asset_id IS NULL} 是同一个手法。
     *
     * <p>{@code clearAutomatically} 让持久化上下文里那份旧状态失效，
     * 否则同一个事务里随后读到的还是 {@code RECEIVING}。
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE UploadSession s
               SET s.status = com.mymedia.upload.UploadStatus.ASSEMBLING
             WHERE s.id = :id
               AND s.status = com.mymedia.upload.UploadStatus.RECEIVING
            """)
    int markAssemblingIfReceiving(@Param("id") Long id);
}
```

在 `src/main/java/com/mymedia/upload/UploadSessionService.java` 里，构造参数再加
`JobQueue jobQueue`（连同字段），在 `receiveChunk` 的末尾追加入队逻辑，并新增两个
package-private 状态方法：

```java
        chunkStore.record(sessionId, index, written);

        // 片齐了就转合并。两道防线：条件 UPDATE 保证只有一个线程拿到 1，
        // 而 dedup_key 上的唯一约束保证即便两个都进来了也只会有一个任务
        if (chunkStore.count(sessionId) == session.getTotalChunks()
                && repository.markAssemblingIfReceiving(sessionId) == 1) {
            jobQueue.enqueue(UploadAssembleJobHandler.JOB_TYPE,
                    "{\"sessionId\":" + sessionId + "}",
                    "upload-assemble:" + sessionId);
        }
    }
```

```java
    /** 合并成功。{@code relativePath} 是文件在媒体库里的最终位置。 */
    @Transactional
    void markCompleted(Long sessionId, String relativePath) {
        repository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("找不到上传会话 id=" + sessionId))
                .completeAt(relativePath);
    }

    /**
     * 永久性失败。
     *
     * <p>只用于「再试一百遍结果也一样」的失败（缺片、大小不符、哈希不符）。
     * 读写异常不走这里——那种要抛出去让任务表按退避重试。
     */
    @Transactional
    void markFailed(Long sessionId, String reason) {
        repository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("找不到上传会话 id=" + sessionId))
                .fail(reason);
    }

    /** 供合并任务读取会话；不做归属校验，因为任务不代表任何用户。 */
    @Transactional(readOnly = true)
    UploadSession forAssembly(Long sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("找不到上传会话 id=" + sessionId));
    }
```

需要新增 import：`com.mymedia.jobs.JobQueue`。

- [ ] **Step 5: 写合并器**

`src/main/java/com/mymedia/upload/UploadAssembler.java`：

```java
package com.mymedia.upload;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.SampledHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把到齐的分片合并成文件、校验、移进媒体库，再触发一次增量扫描。
 *
 * <p><b>本类没有一个方法是 {@code @Transactional} 的。</b>合并一个 20GB 的文件
 * 要读写 40GB，挂着事务会把连接一直占着。状态的每一次改动都是一次独立的短事务，
 * 由 {@code UploadSessionService} 的两个 package-private 方法完成。
 */
@Component
class UploadAssembler {

    private static final Logger log = LoggerFactory.getLogger(UploadAssembler.class);

    /** 重名后缀最多试到 (999)；再撞就是有人在刷，不是巧合。 */
    private static final int MAX_NAME_ATTEMPTS = 999;

    private final UploadSessionService sessionService;
    private final UploadChunkStore chunkStore;
    private final UploadStorage storage;
    private final LibraryService libraryService;
    private final ScanTrigger scanTrigger;

    UploadAssembler(UploadSessionService sessionService,
                    UploadChunkStore chunkStore,
                    UploadStorage storage,
                    LibraryService libraryService,
                    ScanTrigger scanTrigger) {
        this.sessionService = sessionService;
        this.chunkStore = chunkStore;
        this.storage = storage;
        this.libraryService = libraryService;
        this.scanTrigger = scanTrigger;
    }

    /**
     * @throws IOException 读写层面的失败。<b>抛出去</b>让任务表按指数退避重试——
     *         磁盘满、目标目录暂时不可写这类问题下一次可能就好了。
     *         而「哈希不符」这种再试一百遍也一样的失败，在方法内部标 FAILED 并正常返回。
     */
    void assemble(Long sessionId) throws IOException {
        UploadSession session = sessionService.forAssembly(sessionId);

        if (session.getStatus() != UploadStatus.ASSEMBLING) {
            log.info("会话 {} 当前状态是 {}，跳过合并", sessionId, session.getStatus());
            return;
        }
        int received = chunkStore.count(sessionId);
        if (received != session.getTotalChunks()) {
            sessionService.markFailed(sessionId,
                    "分片不齐：收到 " + received + " / " + session.getTotalChunks());
            return;
        }

        Path merged = storage.sessionDir(sessionId).resolve("assembled.bin");
        Files.deleteIfExists(merged);   // 上一次重试留下的残骸

        long total = storage.assembleInto(sessionId, session.getTotalChunks(), merged);
        if (total != session.getTotalSize()) {
            sessionService.markFailed(sessionId,
                    "合并后大小不符：期望 " + session.getTotalSize() + " 字节，实际 " + total);
            storage.deleteSession(sessionId);
            return;
        }

        String actual = SampledHash.of(merged, total);
        if (!actual.equals(session.getContentHash())) {
            // 采样哈希能抓住「传了另一个文件」和「丢了整块首尾」，
            // 抓不住中段几个字节的改动——边界写在 ADR-007
            sessionService.markFailed(sessionId,
                    "内容哈希不符：声明 " + session.getContentHash() + "，实际 " + actual);
            storage.deleteSession(sessionId);
            return;
        }

        Path libraryRoot = Path.of(
                libraryService.getById(session.getTargetLibraryId()).getRootPath());
        String relativePath = availableName(libraryRoot, session.getFilename());
        moveInto(merged, libraryRoot.resolve(relativePath));
        storage.deleteSession(sessionId);

        sessionService.markCompleted(sessionId, relativePath);
        log.info("上传合并完成 session={} -> {}", sessionId, relativePath);

        // 语义层由扫描链路照常建立：上传不认识「电影」也不认识「漫画」，
        // 它只负责把字节放到正确的目录里
        scanTrigger.requestScan(session.getTargetLibraryId());
    }

    /**
     * 目标目录里已经有同名文件时追加 {@code (2)}、{@code (3)}……
     *
     * <p><b>绝不覆盖</b>：那是别人的文件，而扫描是靠路径认文件的——
     * 覆盖等于悄悄换掉一部片子的内容，而目录树纹丝不动。
     */
    private static String availableName(Path libraryRoot, String filename) throws IOException {
        if (!Files.exists(libraryRoot.resolve(filename))) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";

        for (int n = 2; n <= MAX_NAME_ATTEMPTS; n++) {
            String candidate = stem + " (" + n + ")" + extension;
            if (!Files.exists(libraryRoot.resolve(candidate))) {
                return candidate;
            }
        }
        throw new IOException("目标目录里同名文件过多: " + filename);
    }

    /**
     * 临时目录与媒体库很可能不在同一个挂载点上，那时 {@code ATOMIC_MOVE} 会抛异常。
     * 先试原子改名（同盘时几乎瞬时），不行再退回复制。
     */
    private static void moveInto(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.copy(source, target);
            Files.delete(source);
        }
    }
}
```

- [ ] **Step 6: 写任务处理器**

`src/main/java/com/mymedia/upload/UploadAssembleJobHandler.java`：

```java
package com.mymedia.upload;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code UPLOAD_ASSEMBLE}：把到齐的分片合并入库。
 *
 * <p>排成任务而不是挂在「最后一片」那个请求上，为的是从 {@code job} 表白拿
 * 重试、可观测与崩溃恢复三样东西（ADR-003）。
 */
@Component
class UploadAssembleJobHandler implements JobHandler {

    static final String JOB_TYPE = "UPLOAD_ASSEMBLE";

    private final UploadAssembler assembler;
    private final ObjectMapper objectMapper;

    UploadAssembleJobHandler(UploadAssembler assembler, ObjectMapper objectMapper) {
        this.assembler = assembler;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        JsonNode sessionIdNode = payload.get("sessionId");
        if (sessionIdNode == null || !sessionIdNode.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "UPLOAD_ASSEMBLE 任务缺少 sessionId: " + job.getPayload());
        }
        assembler.assemble(sessionIdNode.asLong());
    }
}
```

> `tools.jackson.databind` 不是笔误：Boot 4.1 用的是 Jackson 3。
> 计划 05 的 Global Constraints 里有完整的差异表。

- [ ] **Step 7: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='UploadEndToEndTest,UploadChunkTest,UploadSessionServiceTest,ModularityTests' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/upload src/test/java/com/mymedia/upload
git commit -m "feat: 分片合并、校验、入库与触发扫描

合并排成 UPLOAD_ASSEMBLE 任务而不是挂在最后一片的请求上：
job 表白送重试、可观测与崩溃恢复三样东西（ADR-003 的第五个受益者）。

转 ASSEMBLING 是一条条件 UPDATE，只有一个线程拿到 1；dedup_key 是第二道防线。
判断与写入压成一条语句，与计划 05 的 cover_asset_id 是同一个手法。

失败分两种：哈希不符标 FAILED 且任务算成功（重试无意义），
读写异常抛出去让任务表退避重试。

重名追加 (2) 绝不覆盖——扫描靠路径认文件，覆盖等于悄悄换掉内容。"
```

Expected: `EXIT=0`，`UploadEndToEndTest` 5 个用例通过。

---

## Task 13: 全量验证、ADR-006/007 与讲解文档

**Files:**
- Create: `docs/adr/ADR-006-中文搜索的真实边界.md`
- Create: `docs/adr/ADR-007-采样哈希与秒传的边界.md`
- Create: `docs/walkthrough/06-检索与上传.md`
- Modify: `docs/superpowers/plans/2026-08-17-00-总览与交接.md`（把 06 标记为已完成）
- Modify: `docs/superpowers/plans/STATUS.md`（同上）

**Interfaces:**
- Consumes: 前 12 个任务的全部产出
- Produces: 可交付的阶段成果

- [ ] **Step 1: 跑全量验证**

```bash
cd /d/MyMedia && mvn -B -ntp verify > verify.log 2>&1; echo "EXIT=$?"; grep -E "Tests run:|BUILD" verify.log | tail -20
```

Expected: `EXIT=0`，`BUILD SUCCESS`，`Failures: 0, Errors: 0`。

**若 `ModularityTests` 失败**，按下面的顺序查：

1. 失败信息里出现 `Upload` → 检查 `com/mymedia/upload/package-info.java` 的
   `allowedDependencies`，本计划用到的是 `{"shared", "user", "library", "jobs", "scan"}`。
2. 出现 `Web` → 同理检查 Task 4 建的那份。
3. 出现 `Video`/`Image` 依赖 `Library` 的新类型 → `ShareGrant`、`ShareLinkService`、
   `ShareLinkDto` 都在 `com.mymedia.library` 根包，是模块的公开 API，不需要
   `@NamedInterface`；若报的是找不到命名接口，说明有人把它们挪进了嵌套包。

- [ ] **Step 2: 确认迁移在全新数据库上从 V1 跑到 V16**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=FlywayMigrationTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`。V1→V16 在全新容器上依次执行成功。

**特别要确认的两条顺序依赖**（它们只有在全新库上才会暴露）：

- `V12` 的生成列用的是 `to_tsvector('english', …)` 这个**双参数**形式。
  单参数版本是 STABLE 的，PostgreSQL 会拒绝把它放进生成列，报
  `generation expression is not immutable`。
- `V15__share_link.sql` 引用 `video_item` 与 `image_node`，
  `V16__upload.sql` 引用 `scanned_file`——它们分别由 V6/V8 与 V5 创建。
  编号顺序保证了这一点，但 `mvn clean` 之后跑一次才是证据。

- [ ] **Step 3: 写 ADR-006**

`docs/adr/ADR-006-中文搜索的真实边界.md`：

```markdown
# ADR-006：中文搜索的真实边界

## 状态

已接受（2026-08-17，实施计划 06）

## 背景

本项目的内容以中文为主，而 **PostgreSQL 内置全文检索不切分中文**：
`to_tsvector('simple', '进击的巨人')` 得到的是一个整块 token，搜「巨人」匹配不上。

设计文档 §7.7 由此选择了 `pg_trgm`（三元组相似度 + GIN 索引）作为中文路径，
并称「二者结果合并排序」。**实施前实测了这个方案的边界，发现设计文档的描述需要修正。**

## 实测数据

环境：`postgres:17` 容器，`CREATE EXTENSION pg_trgm`。

| 表达式 | 结果 |
|---|---|
| `similarity('进击的巨人','进击的巨人')` | `1` |
| `similarity('进击的巨人','进击的巨人 最终季')` | `0.6` |
| **`similarity('进击的巨人','巨人')`** | **`0.125`** |
| `pg_trgm.similarity_threshold` 默认 | `0.3` |
| **`'进击的巨人' % '巨人'`** | **`false`** |
| `'进击的巨人' % '进击的'` | `true` |
| `word_similarity('巨人','进击的巨人')` / 阈值 | `0.333` / `0.6` → `<%` 也是 `false` |

10 万行表 + `gin (title gin_trgm_ops)`，`EXPLAIN ANALYZE`：

| 查询 | 索引扫描返回 | Recheck 移除 | 耗时 |
|---|---|---|---|
| `ILIKE '%进击的%'`（3 字） | 2 | 0 | **0.21 ms** |
| `ILIKE '%巨人%'`（2 字） | 20007（全部） | 20004 | **29 ms** |
| `ILIKE '%巨%'`（1 字） | 全部 | 全部 | 29 ms |
| 强制顺序扫描对照（2 字） | — | — | 28.6 ms |

同一张表加 `to_tsvector('english', …)` 生成列 + GIN：

| 查询 | `@@ plainto_tsquery` | `ILIKE '%q%'` |
|---|---|---|
| `bunny` | 命中 `Big Buck Bunny` **和 `The Bunnies Are Running`** | 只命中前者 |
| `movies` | 命中 `Sintel the Movie` | **0 行** |
| `巨人` | 0 行 | 3 行 |

## 决策

1. **匹配谓词一律用 `ILIKE '%q%'`，不用 `%` 操作符。**
   相似度归一化会把短查询压到阈值以下，搜「巨人」匹配不到「进击的巨人」——
   这不是分词失败，是 `%` 根本不适合当子串搜索的谓词。
   `similarity()` 只用来**排序**，不用来**过滤**。
2. **保留 `tsvector` 路径**，但明确它只服务拉丁文：词干化（`movies` → `movie`）
   与复数形式是 `ILIKE` 给不了的，而这些在带英文标题的媒体库里是真实需求。
3. **接受「查询串少于 3 个字符时退化为全表扫描」这个上界。**
   10 万条目 29 ms，对单实例自托管媒体库完全够用。
4. 排序**分层**而非加权求和：子串命中 → 相似度 → `ts_rank` → `sort_title`。

## 理由

`similarity` 落在 0–1，`ts_rank` 通常是 0.0X 量级，**两个分数不在一个尺度上**。
把它们加权求和只是把「我不知道怎么比」包装成一个数字。分层之后每一层内部的
比较都是有意义的，层与层之间是优先级而不是数值。

至于两字查询的全表扫描：pg_trgm 只能从 `%…%` 模式里提取**完全包含在模式内**的
三元组，两个中文字符提不出，索引因此提供不了任何过滤。而两字恰恰是中文最常见的
查询长度（巨人 / 夏目 / 鬼灭）。**知道这个边界在哪儿，比声称「用了 pg_trgm
所以支持中文」有价值得多。**

## 后果

- 搜索在 10 万条目规模内可用，两字查询约 29 ms，三字及以上约 0.2 ms。
- 全局搜索**分区返回不混排**（spec §5.4），因此两个域各自跑一次上面的查询，
  不需要跨域的分数可比性——这让分层排序的设计成本更低。
- 若将来条目数上一个量级、两字查询变得不可接受，收敛方向是 **`pg_bigm`**
  （二元组索引，专为 CJK 设计，两字查询能用上索引）。
  代价是它不在官方镜像里，需要自建 PostgreSQL 镜像，
  **破坏「一键 `docker compose up`」的交付目标**——所以现在不做。

## 备选方案

| 方案 | 中文效果 | 为什么没选 |
|---|---|---|
| `zhparser` / `pg_jieba` 分词插件 | 好 | 同样要自建镜像；且分词器本身需要词表维护 |
| Elasticsearch / MeiliSearch | 好 | 多一个中间件，与「模块化单体、少运维」的定位冲突 |
| 应用层自建倒排索引 | 可控 | 要自己解决持久化、增量更新与一致性，收益不抵成本 |
| `pg_bigm` | 好 | 自建镜像。**这是将来真的不够用时的第一选择** |

## 附注

`similarity` 用的是**字符三元组**，而计划 05 的 `TitleSimilarity`（刮削置信度）
用的是**字符二元组的 Dice 系数**。两者是同一思路在不同约束下的两种落点：
数据库里三元组已经由扩展提供且有索引支持；应用层里中文短标题用二元组明显更准。
这组对照本身就是讲解材料。
```

- [ ] **Step 4: 写 ADR-007**

`docs/adr/ADR-007-采样哈希与秒传的边界.md`：

````markdown
# ADR-007：采样哈希与秒传的边界

## 状态

已接受（2026-08-17，实施计划 06）

## 背景

`SampledHash` 是计划 02 为**改名检测**引入的内容指纹：

```
SHA-256( 8 字节大端 sizeBytes ‖ 采样区 )
采样区 = size ≤ 2MB ? 整个文件 : 首 1MB ‖ 尾 1MB
```

计划 06 的分片上传要用它做两件新事：**秒传判定**与**合并后校验**。
一个为 A 设计的东西被拿去做 B，边界必须重新说清楚。

另有一个数据现实：`scanned_file.content_hash` **绝大多数是 NULL**。
计划 02 只在「消失数与新增数都非零」时才算哈希（那是改名检测唯一需要它的时候），
索引也是 `WHERE content_hash IS NOT NULL` 的部分索引。

## 决策

1. **`SampledHash` 从 `com.mymedia.scan` 提升到 `com.mymedia.shared`，改 public，
   算法一个字节不动。** 它是纯算法、不带任何模块的状态，与 `NaturalSortKey`、
   `MaterializedPath` 并列，符合项目既有的「复用算法，不复用模型」惯例。
2. **秒传分两步**：先按 `content_hash` 查；未命中时取同库内 `size_bytes` 相同且
   哈希为空的候选，**上限 8 个**，现算并写回，再比。
3. **`content_hash` 由客户端提供且必填。** 没有它既没法秒传，也没法在合并后校验。
4. **秒传命中时不产生第二份物理副本**，会话直接指向那个既有的 `scanned_file`。
5. 合并后重算指纹并与声明值比对；不符则标 `FAILED` 且**任务算成功**（不重试）。

## 理由

**为什么不改算法**：`scanned_file.content_hash` 里已经存了按它算出来的值。
改算法会让所有既有指纹失效，下一次扫描会把全部文件当成新文件——
改名检测全面失灵，用户看到的是"整个库重新入库了一遍"。

**为什么这个算法形状让秒传变便宜**：浏览器用 `File.slice` 读首尾各 1MB、
`crypto.subtle.digest('SHA-256', …)` 算一次，就能在**不读整个文件**的前提下
拿到与服务端一致的指纹。若当初选的是全量 SHA-256，用户要传一个 20GB 的文件
就得先在本地读完 20GB 才能问「你有没有」，秒传的意义所剩无几。
这是一处「早先为 A 做的决定，后来在 B 上白捡了一个好处」。

**为什么要有第二步**：只按哈希查会几乎全部落空（见背景），秒传形同虚设。
现算候选让它对存量文件也有效，而且**算过的哈希会留下**——
一次秒传尝试顺带把哈希补齐了，下次更快。

**为什么上限是 8**：一个库里可能有几百个大小恰好相同的文件（同一台设备导出的
视频尤其容易撞）。不设限，一个创建会话的请求就可能读上几 GB。
8 次 × 2MB ≈ 16MB，是一次 HTTP 请求可以承受的 I/O。

## 后果 —— 这三件事必须说清楚

### 1. 采样哈希会漏掉中段差异

两个首尾相同、仅中段不同的大文件得到相同指纹。这意味着：

- **秒传可能误判**：一个与库内某文件首尾相同、中段不同的大文件会被当成"已存在"，
  用户以为传上去了，实际库里还是旧的那份。
- **合并后校验抓不住中段损坏**。

**能抓住的是**：传了另一个文件、丢了整块首尾、总长度不对。

**为什么可以接受**：逐字节完整性由三层承担——TCP 校验和、TLS 的 AEAD、
以及每一片 `written != expected` 的当场核对。剩下的风险窗口是
"传输层全部通过、但中段内容恰好不同且首尾与库内某文件完全一致"，
在自托管媒体库这个场景里可以忽略。

**要说的是这句，不是「做了完整性校验」。** 后者把边界含糊掉了。

### 2. 秒传对同一个库以外的内容无效

查询按 `library_id` 分区（也是既有部分索引的形状）。跨库的相同文件不会秒传。
这是有意的：媒体库的根目录可能在不同磁盘上，"库 A 里有"不代表"库 B 里也能看到"。

### 3. 上限 8 意味着秒传会"漏"

同尺寸候选超过 8 个时，第 9 个之后的不会被现算，秒传就会落空，走正常上传。
**落空的代价只是慢，不是错**——这是一个安全方向的失败。

## 备选方案

| 方案 | 为什么没选 |
|---|---|
| 全量 SHA-256 | 客户端要先读完整个文件才能发起上传；服务端扫描一个 20GB 视频要数分钟 |
| 分块 Merkle 树 | 能定位到具体哪一块坏了，但客户端与服务端都要实现一套树；本项目的失败处理是"整个重传"，定位能力用不上 |
| 只按大小判重 | 误判率高到不可用 |
| 不做秒传 | 用户把同一部片子传第二遍要等几十分钟，而系统明明已经有了 |

## 附注

`content_hash` 的补齐是**秒传的副产品**而不是一个独立的后台任务。
若将来发现哈希覆盖率仍然太低（例如很多库从来没人上传过），
正确的收敛方式是加一个低优先级的 `HASH_BACKFILL` 任务类型，
而不是让扫描在每次对账时全量算哈希——那会把扫描从分钟级拖到小时级。
````

- [ ] **Step 5: 写讲解文档**

`docs/walkthrough/06-检索与上传.md`，按既有讲解文档的结构
（做了什么 / 为什么这么做 / 坑在哪 / 怎么自己验证），必须覆盖下面十条：

1. **两条搜索路径各管各的**：三元组管中文子串，`tsvector` 管拉丁文词干化。
   配上 ADR-006 的实测表格，并明确说出「两字查询是全表扫描」这个上界。
   自验方法：
   ```sql
   EXPLAIN ANALYZE SELECT id FROM video_item WHERE title ILIKE '%巨人%';
   EXPLAIN ANALYZE SELECT id FROM video_item WHERE title ILIKE '%进击的%';
   ```
2. **分层排序 vs 加权求和**：为什么两个不同量纲的分数不能相加。
3. **`LIKE` 模式转义**：搜 `50%` 会发生什么，以及 `ESCAPE '\'` 的必要性。
   这既是正确性问题，也是「用户输入不能直接拼进模式」的一般纪律。
4. **域分区的第三次数据库级强制**：标签关联表的复合外键（指向 ADR-001），
   并说明它与 `video_item`、`collection` 是同一个手法。
5. **用户态四张表**：进度 ×2 + 收藏 ×2，为什么它们住在各自的领域模块而不是
   一个公共的「用户数据」模块（会立刻需要一个多态的「目标类型」列）。
6. **分享链接为什么是两个可空外键 + CHECK**：多态外键在 PostgreSQL 里
   建不了引用完整性约束。配一个实际演示：
   ```sql
   -- 删掉条目，指向它的分享链接跟着消失
   DELETE FROM video_item WHERE id = 1;
   SELECT count(*) FROM share_link WHERE video_item_id = 1;   -- 0
   ```
7. **能力令牌与 HMAC 票据**：为什么无效/过期/已撤销都返回 404；
   为什么带密码的链接不能每页重验 bcrypt（20 页 × 100ms）；
   票据为什么绝不能比链接活得久；`MessageDigest.isEqual` 与 `String.equals`
   的区别（定时旁路）。
8. **`/api/share/**` 与 `/api/shares` 只差一个字母**：路径模式按整段比较，
   所以不会互相匹配——但这件事值得一个测试，因为"我以为是这样"在安全配置上不够。
9. **分片上传绕开 multipart**：默认 1MB/10MB 上限、解析器会二次落盘、
   而我们的元信息全在 URL 里。以及 `receiveChunk` 为什么不能是事务方法。
10. **秒传的三条边界**（指向 ADR-007）：采样哈希漏中段、只在库内有效、
    候选上限 8 个会让秒传"漏"。**照实写，不要写成"实现了秒传"就完事。**

再加一段「自己跑一遍完整上传」的操作说明，包含客户端算指纹的那 20 行 JS：

```javascript
// 采样哈希：8 字节大端长度 ‖ (≤2MB ? 整个文件 : 首 1MB ‖ 尾 1MB)
async function sampledHash(file) {
  const WINDOW = 1024 * 1024;
  const header = new DataView(new ArrayBuffer(8));
  header.setBigUint64(0, BigInt(file.size), false);   // false = 大端

  const parts = [header.buffer];
  if (file.size <= 2 * WINDOW) {
    parts.push(await file.arrayBuffer());
  } else {
    parts.push(await file.slice(0, WINDOW).arrayBuffer());
    parts.push(await file.slice(file.size - WINDOW).arrayBuffer());
  }

  const digest = await crypto.subtle.digest('SHA-256', await new Blob(parts).arrayBuffer());
  return [...new Uint8Array(digest)]
      .map(b => b.toString(16).padStart(2, '0')).join('');
}
```

> 这段 JS 写进讲解文档而不是仓库代码：计划 07 才建前端。
> 它在这里的作用是**证明服务端选的指纹算法客户端真的算得出来**——
> 这是 ADR-007 那条「算法形状让秒传变便宜」的论据，不是一句想当然。

- [ ] **Step 6: 更新总览与交接**

修改 `docs/superpowers/plans/2026-08-17-00-总览与交接.md`：

1. §1 的计划表里把 06 那一行改成 `✅ 已写（13 任务）`，并把「⬜ **下一份**」
   移到 07。
2. §2 的迁移编号一行补上 `V12–V16（06）`，并注明 **V17 起留给计划 07/08**。
3. §2 的任务类型一行补上 `UPLOAD_ASSEMBLE`(06)。
4. §3 的「已实测验证的事实」表追加三行：
   - **`'进击的巨人' % '巨人'` 为 false**，`similarity` 只有 `0.125`；
     两字中文查询用不上 GIN trgm 索引（10 万行 29ms）—— 验证方式：`postgres:17` 实跑 + `EXPLAIN ANALYZE`
   - **`to_tsvector('english', …)` 对拉丁文有真实价值**（`movies` 命中 `Sintel the Movie`，
     `ILIKE` 命中 0 行）—— 同上
   - **Boot 4 的 multipart 属性没改名**（仍是 `spring.servlet.multipart.*`，
     经 `starter-webmvc → spring-boot-webmvc → spring-boot-servlet` 传递引入，
     `compile` 作用域，无需新增依赖），但默认上限 1MB/10MB —— 验证方式：`mvn dependency:tree`
5. §5「已知遗留」追加计划 06 的遗留项（见本计划 Self-Review 第 5 节）。
6. 新增一节记录本计划落地的两个 ADR（006/007），与计划 05 的 004/005 并列。

修改 `docs/superpowers/plans/STATUS.md`：

1. §1「一句话现状」改成：计划 01 的代码已执行完毕并合入 `main`；
   计划 02–06 的计划文本已写完；**下一步要么执行计划 02，要么开始写计划 07**。
2. §3.3 的表格补齐 Task 6–13。
3. §4「未决问题」删掉第 1、2 条（计划 06 已写完、草稿已并入），
   第 3 条（`VideoItem.getCoverAssetId()`）改成「已在 Task 6 Step 5 明确补上，
   且是只读映射」。
4. §5「下一步操作」把 5.1 换成「计划 07（前端）待写」。

- [ ] **Step 7: 最终提交**

```bash
cd /d/MyMedia
rm -f verify.log t.log
git add docs
git commit -m "docs: 完成检索与上传阶段的 ADR 与讲解文档

ADR-006 记录中文搜索的真实边界：% 操作符不能当子串谓词（实测 0.125 < 0.3），
两字查询用不上 GIN trgm 索引（10 万行 29ms）。知道边界在哪儿比声称'支持中文'有价值。

ADR-007 记录采样哈希被复用到秒传后的三条边界：漏中段、只在库内有效、
候选上限 8 会让秒传'漏'。照实写，不含糊成'做了完整性校验'。

讲解文档里附上客户端算指纹的那 20 行 JS——它是'算法形状让秒传变便宜'
这条论据的证明，不是想当然。"
```

Expected: `EXIT=0`。

---

## Self-Review

### 1. Spec 覆盖

| spec 章节 / 要求 | 落点 |
|---|---|
| §4.2 模块清单里的 `web`（一直声明、从未创建） | Task 4 |
| §4.2 模块清单里的 `upload` | Task 10–12 |
| §4.2 `library` 负责「媒体库定义、访问控制、**分享链接**」 | Task 8（`ShareLinkService` 住 `library`） |
| §4.2 依赖规则 3「`video` 与 `image` 互不依赖」 | 全程保持；Task 4 的 `web` 是唯一同时看见两个域的地方，由 `ModularityTests` 强制 |
| §5.3 接口按领域切分而非按技术层 | Task 2/3 的 `/api/video/search` 与 `/api/image/search`；Task 6 的 `/api/video/items/{id}/tags`；Task 7 的两套收藏端点 |
| §5.4 「唯一交汇点是全局搜索，且结果**分区展示，不混排**」 | Task 4（响应体是两个独立数组） |
| §6.2 `share_link`（两个可空外键 + `CHECK num_nonnulls = 1`） | Task 8 的 `V15__share_link.sql` |
| §6.2 `upload_session` / `upload_chunk` 的字段清单 | Task 10 的 `V16__upload.sql`（另加 `relative_path` / `instant` / `scanned_file_id` / `last_error`，见 §4） |
| §6.2 `tag(id, domain, name, slug)`，`(domain, slug)` 唯一，视频与图片标签互不混用 | Task 5 的 `V13__tags.sql` |
| §6.3 / §6.4 的 `search_vector tsvector` 生成列 | Task 1 的 `V12__search_columns.sql`（计划 03 的 V6 与计划 04 的 V8 都漏了这一列，本计划补上） |
| §6.5 `video_favorite` / `image_favorite`，「允许收藏任意节点，**包括文件夹**」 | Task 7 的 `V14__favorites.sql` + `ImageFavoriteServiceTest` 的专门用例 |
| §7.6 步骤 1（创建会话，携带 filename/total_size/content_hash/target_library_id） | Task 10 |
| §7.6 步骤 2（秒传） | Task 10 的 `InstantUploadResolver` |
| §7.6 步骤 3（断点续传：返回已接收的 `chunk_index` 列表） | Task 11 |
| §7.6 步骤 4（逐片上传，写入临时目录） | Task 11 |
| §7.6 步骤 5（按序合并 → 校验整体哈希 → 移入目标库路径 → 触发增量扫描） | Task 12 |
| §7.7 中文路径用 `pg_trgm` GIN | Task 1 建索引、Task 2/3 用它；**并按实测修正了 spec 的说法**，见 §4 |
| §7.7 拉丁文路径保留 `tsvector` 支持词干化与相关度 | Task 1 建生成列、Task 2/3 的第二条路径 |
| §7.7「二者结果合并排序」 | Task 2/3 的分层排序（子串 → 相似度 → `ts_rank` → `sort_title`） |
| §7.7 的 P0 验收动作（`pg_trgm` 实测） | **已在写计划前完成**，结果进 Global Constraints 与 ADR-006 |
| §11 P10 搜索 / 标签 / 收藏 / 分享链接 | Task 1–9 |
| §11 P11 分片上传 / 断点续传 / 秒传 | Task 10–12 |
| §12 ADR #3b「为什么中文搜索用 `pg_trgm` 而非 `tsvector` 或 Elasticsearch」 | Task 13 的 ADR-006 |
| §12 ADR #7「为什么改名检测使用采样哈希而非全量哈希」 | Task 13 的 ADR-007（并补上它被复用到秒传之后的新边界） |
| §12 ADR #9「为什么不引入 Redis / Elasticsearch」 | ADR-006 的备选方案表给出了搜索这一侧的论据 |
| §10 交付物：讲解文档 | Task 13 |

**本计划范围内、但明确不做的两件事**，理由写在这里而不是留白：

- **搜索结果不做高亮片段（snippet）。** `ts_headline` 只对 `tsvector` 路径有效，
  而中文走的是 `ILIKE`，两条路径给不出一致的高亮。要么前端自己按查询串在标题里
  标记（标题很短，够用），要么什么都不做。**服务端给一个只有一半情况下存在的
  字段，比不给更糟。**
- **标签没有重命名与合并端点。** 建、列、删、贴四件事已经能用；
  重命名会牵出 slug 唯一键冲突的处理，合并会牵出关联表去重，
  两者都属于「管理后台」的范畴，P10 的验收不需要。

### 2. 占位符扫描

已逐条检查，没有 "TBD" / "TODO" / "参照 Task N" / "适当处理错误" 一类的写法。
两个域对称的代码（Task 7 的收藏、Task 8 的两个创建控制器）**都逐字写全了**，
没有用「照抄上面改个名」的替换表——执行者可能只拿到其中一半。

三处**看起来像**留白、实际是有意为之的地方，各自写明了理由：

- Task 10 的 `UploadController.get` 传 `List.of()` 作为已收分片：那时
  `UploadChunkStore` 还不存在，返回空列表比返回一个假清单诚实。Task 11 Step 6 补全。
- Task 11 结束时片齐的会话仍停在 `RECEIVING`：触发合并是 Task 12 的事，
  任务末尾有一句明确的说明，避免被当成缺陷。
- Task 6 Step 1 最后一个用例里 `derived_asset.source_scanned_file_id` 传 `NULL`：
  依赖计划 05 把这一列建成可空外键，注释里写了「若那边改成 NOT NULL 该怎么调整」。

### 3. 类型一致性

逐个核对了跨任务的签名：

- `SearchQuery.of / normalized() / lowered() / likePattern() / usesTrigramIndex()`
  —— Task 1 定义，Task 2、3 使用，一致。
- `VideoSearchHit` / `ImageSearchHit` 的字段顺序 —— Task 2、3 定义，Task 4 原样透出，一致。
- `TagService`：`findOrCreate / findByDomain / getById / delete`（Task 5）+
  `tagsOf / setTags / targetIdsWithTag`（Task 6），参数名统一为 `targetId`。
- `TagDto`：`Response.from`（Task 5）、`SetTagsRequest` / `TaggedTarget`（Task 6），一致。
- `VideoCatalogService.findByIds(Collection<Long>)` / `ImageCatalogService.findByIds(Collection<Long>)`
  —— Task 6 定义，Task 6 的 `TagLinkController` 与 Task 7 的两个收藏服务使用，一致。
- **`VideoItem.getCoverAssetId()` 在计划 03 与 05 里都不存在**，Task 6 Step 5 明确创建
  （只读映射）。Task 2 的 `VideoSearchHit.coverAssetId` 是走 SQL 直读的，不依赖这个 getter，
  两条路径互不冲突。
- 收藏服务的列表方法**刻意不同名**：`listItems`（视频）/ `listNodes`（图片）。
  两个域的元素类型本来就不同（`VideoItem` / `ImageNode`），强行同名只会让阅读者
  以为它们可以互换。其余三个方法（`add` / `remove` / `isFavorite`）完全同名同签名。
- `ShareGrant(shareLinkId, libraryId, videoItemId, imageNodeId, passwordProtected, expiresAt)`
  + `isVideo()` / `isImage()` —— Task 8 定义，Task 9 的两个域控制器与两个 `locateForShare` 使用，一致。
- `ShareLinkService`：`createForVideoItem / createForImageNode / listCreatedBy / revoke / resolve`
  （Task 8）+ `unlock / resolveUnlocked`（Task 9）。构造参数在 Task 9 增加 `ShareTicket`，
  两处都写明了。
- `ShareLinkDto`：`CreateRequest / Response.from`（Task 8）+
  `UnlockRequest / UnlockResponse / PublicView`（Task 9），全部 public，两个领域模块都能用。
- `ShareTicket.issue(String, Instant, Instant)` / `verify(String, String, Instant)`
  —— Task 9 定义；`ShareTicketTest` 用 `new ShareTicket(String, Duration)` 直接构造，
  与那个双参构造器对得上（纯单元测试不进 Spring 容器）。
- `VideoStreamService.locate / locateForShare / StreamTarget`、
  `ImagePageService.locate / locateForShare / open / PageTarget` —— Task 9 的两处 Modify
  都把原方法完整重写了一遍，没有留「在某某行后面插一段」这种指令。
- `VideoRangeResponder.respond(StreamTarget, String, String)` —— Task 9 定义，
  `VideoStreamController` 与 `VideoShareController` 两个调用点参数顺序一致。
- `SampledHash.of(Path, long)` —— Task 10 搬家后签名不变，Task 10 的 `ScannedFileHashService`、
  Task 12 的 `UploadAssembler`、以及三个测试使用，一致。
- `ScannedFileHashService.findActiveByContentHash / findActiveBySizeWithoutHash / computeAndStoreHash`
  —— Task 10 定义，`InstantUploadResolver` 使用，一致。
- `UploadStatus` 四个取值与 `V16` 的 `CHECK` 约束逐字对齐。
- `UploadSession` 的三个状态方法 `completeInstantly / completeAt / fail` ——
  Task 10 定义，Task 10（秒传）与 Task 12（合并成功 / 失败）使用。
  **实体上刻意没有 `markAssembling()`**：那一步必须走 Task 12 的条件 UPDATE，
  留一个实体方法只会诱人去用错的那条路，Task 10 的代码里有注释说明。
- `UploadSessionService`：`create / get`（Task 10）、`receiveChunk / receivedChunks`（Task 11）、
  `markCompleted / markFailed / forAssembly`（Task 12）。构造参数分三次增补
  （`UploadStorage` + `UploadChunkStore` 在 Task 11，`JobQueue` 在 Task 12），每次都写明了。
- `UploadStorage.sessionDir / chunkPath / writeChunk / deleteSession`（Task 11）
  + `assembleInto`（Task 12），一致。
- `UploadChunkStore.record / receivedIndexes / count` —— Task 11 定义，
  Task 11 与 Task 12 使用，一致。
- `UploadDto.Response.from(UploadSession, List<Integer>)` —— Task 10 定义为两个参数，
  Task 10 的两个调用点传 `List.of()`，Task 11 改成传真实清单，签名没变。
- `UploadAssembleJobHandler.JOB_TYPE = "UPLOAD_ASSEMBLE"` —— Task 12 定义，
  同一任务里的 `UploadSessionService` 引用常量而不是字面量。
- 复用既有 API：`JobQueue.enqueue(type, payloadJson, dedupKey)`、`JobPoller.pollOnce()`、
  `ScanTrigger.requestScan(libraryId)`、`LibraryAccessService.canAccess(userId, libraryId)`、
  `UserQueryService.findByUsername`、`VideoCatalogService.getItem / getFile / filesOf`、
  `ImageCatalogService.getNode / getFile / pagesOf`、`ImageBrowseService.childNodes` ——
  全部按各自计划里的既有签名调用，没有臆造。

### 4. 与 spec 的四处有意偏离

每一处都是「实测或推演之后认为 spec 需要修正」，不是遗漏：

1. **§7.7 说「`pg_trgm` 天然支持中文子串匹配与模糊搜索」，实测下这句话要拆开看。**
   子串匹配成立（靠 `ILIKE` + GIN 提三元组），模糊搜索在两字查询上不成立
   （`similarity('进击的巨人','巨人') = 0.125` < 阈值 `0.3`），
   而且**查询串少于 3 字时索引提供不了任何过滤**。
   本计划因此把 `%` 操作符从匹配谓词降级为纯排序用途，并接受两字全表扫描的上界。
   全部证据与退路写进 ADR-006。
2. **`upload_session` 比 spec 的字段清单多了四列**：`relative_path`（落库后的位置）、
   `instant`（是不是秒传）、`scanned_file_id`（秒传命中的既有文件）、`last_error`。
   前三列是客户端问「结果怎么样」时必须给出的答案，最后一列是失败时唯一有用的东西。
   spec §6.2 的清单是数据模型草图，不是完整 DDL。
3. **分享链接的创建端点没有住在 `library`。** spec §4.2 把「分享链接」归给 `library`，
   `ShareLinkService` 与 `share_link` 表确实都在那儿；但**创建**需要先校验
   「目标条目存在且你有权访问」，那要 `VideoCatalogService`，而 `library` 不许依赖 `video`。
   于是创建端点分到两个领域模块，管理端点（列出 / 撤销）留在 `library`。
   spec 说的是「谁拥有这个概念」，不是「哪个类接 HTTP 请求」。
4. **标签归 `metadata`，而 spec §4.2 的模块清单里没提标签该归谁。**
   选 `metadata` 是因为它已经同时持有 `video` 与 `image` 两条依赖边（计划 05 为刮削建立的），
   放这里不新增任何模块间依赖，关联表也与 `scrape_candidate` 是同一个先例。

### 5. 已知遗留（写给计划 07 及之后）

- **`mymedia.share.secret` 默认为空时每次启动随机生成**，重启后带密码的分享链接
  需要访客重新输一次密码。这是为「一键启动」让路的取舍，已经打了 WARN 日志。
  计划 08 的 README 里应当把「生产部署要配一个固定值」写进部署清单。
- **秒传只在库内生效，且同尺寸候选上限 8 个**，超过就会落空走正常上传。
  落空的代价只是慢不是错，但若实测发现命中率低得离谱，收敛方向是加一个低优先级的
  `HASH_BACKFILL` 任务类型，**而不是**让扫描在每次对账时全量算哈希。
- **上传的临时目录没有过期清理。** 用户创建了会话却再也不传，
  `{tempRoot}/{sessionId}/` 会一直留着。加一个定期任务扫 `status = 'RECEIVING'` 且
  `created_at` 超过 N 天的会话即可，属于运维完善，不属于 P11 的验收范围。
- **`image_node.coverAssetId` 在计划 04 里是可写映射，而计划 05 用 `JdbcTemplate` 的
  `UPDATE … WHERE cover_asset_id IS NULL` 写它。** 这两件事叠在一起有一个隐患：
  一个在写入之前加载、在写入之后刷新的 `ImageNode` 会把缓存里的 `null` 刷回去。
  本计划 Task 6 给 `VideoItem` 补映射时选了 `insertable = false, updatable = false`
  正是为了避开它。**执行计划 04/05 时建议把图片域也改成只读映射**——
  `ImageNode.assignCover` 若确实没有别的调用方，一并删掉。
- **分享链接的访问没有任何速率限制。** 令牌有 128 位以上的熵，暴力枚举不现实；
  但带密码的链接的 `unlock` 端点是可以被爆破的（bcrypt 本身慢，已经是一层缓冲）。
  若要加，正确的位置是一个基于 `token` 的计数器，不是全局限流。
- **搜索没有分页，只有 `limit`（上限 200）。** 媒体库的搜索是「找那一部」，
  不是「浏览全部结果」，翻到第 5 页几乎不会发生。真需要时按
  `(score, id)` 做游标分页，不要用 `OFFSET`。
- 迁移编号 **V17 起留给计划 07/08**；ADR **008 起可用**；
  任务类型 `UPLOAD_ASSEMBLE` 已占用，`TRANSCODE` 仍是预留未用。
