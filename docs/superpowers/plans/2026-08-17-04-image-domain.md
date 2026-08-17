# MyMedia 实施计划 04：图片域

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现图片域的完整链路：任意深度节点树、CBZ 压缩包索引与流式读取、目录改名与移动的子树重写、分页阅读、阅读进度与阅读模式覆盖。

**Architecture:** `image` 模块实现 `scan` 模块定义的 `LibraryContentBuilder` SPI，把物理文件构建成节点树。与视频域**刻意不对称**——视频域是语义树（作品/季/文件），图片域是任意深度的自由树，「书」与「文件夹」不是互斥类型而是同一节点的两种能力。树算法（物化路径、自然排序）复用 `shared`，但两个域各用各的表：**复用算法，不复用模型**。`image` 与 `video` 互不依赖，由架构测试强制。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · PostgreSQL 17 · Apache Commons Compress 1.28.0（ZIP 随机访问）

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`（覆盖 §5.3 接口分区、§6.4 图片域数据模型、§6.5 阅读进度、§7.1 子树移动、§7.4 图片流式阅读、路线图 P6–P7）

**前置计划:** 01 基础设施、02 扫描框架、03 视频域 必须全部完成且 `mvn verify` 通过。

---

## Global Constraints

**继承计划 01、02、03 的全部 Global Constraints。执行前必须先读一遍计划 01 的该章节。**

本计划新增：

### 依赖坐标（已实测：下载 jar + `javap` + 实跑验证）

| 用途 | 坐标 | 版本 |
|---|---|---|
| ZIP 随机访问、条目名编码回退 | `org.apache.commons:commons-compress` | `1.28.0` |

计划 02 的约束表中登记了这个坐标但**没有真的写进 `pom.xml`**（当时用不到）。本计划 Task 4 负责添加。实测确认：

```bash
curl -s https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/maven-metadata.xml
# <latest>1.28.0</latest>  <release>1.28.0</release>
```

它会传递引入 `commons-io:commons-io:2.20.0`（构建器 API 来自 commons-io 的 `AbstractStreamBuilder`），**不需要手写这条依赖**。

### 绝不把压缩包解压到磁盘

CBZ 本质是 ZIP。整本漫画解压到临时目录意味着磁盘占用翻倍、清理逻辑、并发冲突三重代价，而 ZIP 的中央目录区天然支持随机访问单个条目。**任何"先解压再读"的实现都不接受。**

### 压缩包条目名编码：实测结论

`ZipFile.builder()...setCharset(X)` 中的 `X` 是**回退编码，不是强制编码**。实测（`jar` 生成的 UTF-8 归档，分别用 UTF-8 与 GBK 打开）：

| 条目 | UTF-8 打开 | GBK 打开 | 通用位标记 |
|---|---|---|---|
| `第1章/001.jpg` | `e7acac31e7aba02f...` | `e7acac31e7aba02f...` | `usesUTF8ForNames = true` |

两种配置得到**完全相同的字节**。原因：ZIP 的通用位标记第 11 位若为 1，条目名就是 UTF-8，commons-compress 直接按 UTF-8 解，配置的 charset 根本不参与。

**因此把回退编码设成 `GBK` 是安全的**——它只作用于**没有**打这个标记的归档（Windows 中文压缩工具的典型产物），不会破坏 UTF-8 归档。这条是本计划 `mymedia.image.archive-charset` 配置项存在的全部理由。

### 「书」与「文件夹」不是互斥类型

spec §6.4 的核心设计，实现时不得退化成 `type = BOOK | FOLDER` 的二选一：

- `direct_page_count > 0` → 可阅读
- `child_node_count > 0` → 可浏览
- 两者皆大于 0 → **同时提供两个入口**
- `reading_mode` 允许用户随时推翻自动判定

### 页不建树节点

一本 500 页的漫画若每页建一个 `image_node`，树会被撑爆。页只是挂在节点下的 `image_file` 行。**任何"每页一个节点"的实现都是错的。**

### 与 spec §7.4 的一处偏离（已定稿）

spec 写的是「**首次打开时**创建 `ARCHIVE_INDEX` job」。本计划改为**扫描发现压缩包时立即排队索引**。

理由：扫描本身就是后台任务，发现即排队多出的成本是一次中央目录区读取（毫秒级）；而"首次打开才索引"会让第一次打开必须等待异步任务完成，前端要么轮询任务状态、要么显示空白页。**代价相同，体验差一档。** 索引"只做一次、不是每次访问都做"这个核心意图完全保留。

---

## File Structure

```
src/main/java/com/mymedia/image/
├── package-info.java
├── ImageNode.java                    实体 → 表 image_node
├── ImageSourceKind.java              枚举 DIRECTORY / ARCHIVE
├── ImageReadingMode.java             枚举 AUTO / FORCE_BOOK / FORCE_FOLDER
├── ImageNodeStatus.java              枚举 ACTIVE / MISSING
├── ImageFile.java                    实体 → 表 image_file
├── ImageNodeRepository.java          package-private
├── ImageFileRepository.java          package-private
├── ImageNodeIndexer.java             package-private：路径 → 节点树 find-or-create
├── ImageContentBuilder.java          package-private：实现 scan 的 SPI
├── ArchivePage.java                  package-private：压缩包内的一个图片条目
├── ImageArchiveReader.java           package-private：ZIP 随机访问
├── ArchiveIndexJobHandler.java       package-private：ARCHIVE_INDEX 任务
├── ImageLibraryRecalculator.java     package-private：页序重编号 + 计数聚合 + 空节点回收
├── ImageTreeRelocator.java           package-private：目录改名与移动（子树前缀重写）
├── ImageScanFinalizer.java           package-private：扫描完成后的编排入口
├── ImageCatalogService.java          public API：节点与页查询、阅读模式覆盖
├── ImageBrowseService.java           public API：树浏览与面包屑
├── ImagePageService.java             public API：单页定位、鉴权与开流
├── ImageProgressService.java         public API：阅读进度
├── ImageProgress.java                实体 → 表 image_progress
├── ImageProgressRepository.java      package-private
├── event/ImageNodeCreated.java       public 事件
└── web/
    ├── ImageNodeDto.java
    ├── ImageNodeController.java
    ├── ImageBrowseController.java
    ├── ImagePageController.java
    └── ImageProgressController.java

src/main/resources/db/migration/
├── V8__image_domain.sql
└── V9__image_progress.sql

src/test/java/com/mymedia/image/
├── ImageDomainConstraintTest.java
├── ImageNodeIndexerTest.java
├── ImageContentBuilderTest.java
├── ImageArchiveReaderTest.java
├── ArchiveIndexJobHandlerTest.java
├── ImageLibraryRecalculatorTest.java
├── ImageTreeRelocatorTest.java
├── ImageBrowseServiceTest.java
├── ImagePageControllerTest.java
└── ImageProgressServiceTest.java
```

---

## Task 1: 图片域表与实体

**Files:**
- Create: `src/main/resources/db/migration/V8__image_domain.sql`
- Create: `src/main/java/com/mymedia/image/package-info.java`
- Create: `src/main/java/com/mymedia/image/ImageSourceKind.java`
- Create: `src/main/java/com/mymedia/image/ImageReadingMode.java`
- Create: `src/main/java/com/mymedia/image/ImageNodeStatus.java`
- Create: `src/main/java/com/mymedia/image/ImageNode.java`
- Create: `src/main/java/com/mymedia/image/ImageFile.java`
- Create: `src/main/java/com/mymedia/image/ImageNodeRepository.java`
- Create: `src/main/java/com/mymedia/image/ImageFileRepository.java`
- Test: `src/test/java/com/mymedia/image/ImageDomainConstraintTest.java`

**Interfaces:**
- Consumes: `MediaLibrary`、`LibraryDomain`、`LibraryService`（计划 01 Task 7）、`ScannedFile`（计划 02 Task 1）、`NaturalSortKey`、`MaterializedPath`（计划 03 Task 1、2）、`AbstractIntegrationTest`（计划 01 Task 4）
- Produces:
  - `public enum ImageSourceKind { DIRECTORY, ARCHIVE }`
  - `public enum ImageReadingMode { AUTO, FORCE_BOOK, FORCE_FOLDER }`
  - `public enum ImageNodeStatus { ACTIVE, MISSING }`
  - `public class ImageNode` — getter：`Long getId()`、`Long getLibraryId()`、`Long getParentId()`、`String getMaterializedPath()`、`String getSortPath()`、`int getDepth()`、`String getName()`、`String getSortKey()`、`String getTitle()`、`String getDisplayName()`、`ImageSourceKind getSourceKind()`、`Long getArchiveScannedFileId()`、`ImageReadingMode getReadingMode()`、`int getDirectPageCount()`、`int getChildNodeCount()`、`int getTotalPageCount()`、`Long getCoverAssetId()`、`ImageNodeStatus getStatus()`；能力判定 `boolean isReadable()`、`boolean isBrowsable()`
  - `public class ImageFile` — getter：`Long getId()`、`Long getScannedFileId()`、`Long getNodeId()`、`int getPageIndex()`、`String getSortKey()`、`String getArchiveEntryName()`、`Integer getWidth()`、`Integer getHeight()`、`String getFormat()`、`boolean isAnimated()`

- [ ] **Step 1: 写会失败的约束测试**

`src/test/java/com/mymedia/image/ImageDomainConstraintTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary library(LibraryDomain domain) {
        return libraryService.create("库" + UUID.randomUUID(), domain, "/media/" + UUID.randomUUID());
    }

    private Long insertDirectoryNode(Long libraryId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, ?, ?, 'DIRECTORY')
                RETURNING id
                """, Long.class, libraryId, name, name);
    }

    private Long insertScannedFile(Long libraryId, String relativePath, String extension) {
        return jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, 100, now(), ?, 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, libraryId, relativePath, extension);
    }

    @Test
    void imageNodeCanBeCreatedInImageLibrary() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        Long id = insertDirectoryNode(imageLib.getId(), "画师A");

        assertThat(id).isNotNull();
    }

    @Test
    void imageNodeCannotLiveInVideoLibrary() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        // 域分区的核心保证：复合外键让图片节点无法落进视频库
        assertThatThrownBy(() -> insertDirectoryNode(videoLib.getId(), "不该存在"))
                .hasMessageContaining("fk_image_node_library_domain");
    }

    @Test
    void domainColumnCannotBeSetToVideo() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'VIDEO', '/', '/', 0, 'x', 'x', 'DIRECTORY')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_is_image");
    }

    @Test
    void rejectsUnknownSourceKind() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, 'x', 'x', 'RAR_VOLUME')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_source_kind");
    }

    @Test
    void archiveNodeMustReferenceItsArchiveFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        // ARCHIVE 节点没有压缩包本体就无从读页，数据库层面直接堵死
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, 'vol01', 'vol01', 'ARCHIVE')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_archive_ref");
    }

    @Test
    void directoryNodeMustNotReferenceAnArchiveFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long scannedId = insertScannedFile(imageLib.getId(), "vol01.cbz", "cbz");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key,
                     source_kind, archive_scanned_file_id)
                VALUES (?, 'IMAGE', '/', '/', 0, '目录', '目录', 'DIRECTORY', ?)
                """, imageLib.getId(), scannedId))
                .hasMessageContaining("ck_image_node_archive_ref");
    }

    @Test
    void siblingNamesAreUniqueEvenAtRootWhereParentIsNull() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        insertDirectoryNode(imageLib.getId(), "同名");

        // PostgreSQL 默认 NULL 互不相等，(library_id, NULL, name) 不会冲突。
        // 本表用 UNIQUE NULLS NOT DISTINCT（PG 15+）修掉这个经典漏洞。
        assertThatThrownBy(() -> insertDirectoryNode(imageLib.getId(), "同名"))
                .hasMessageContaining("uq_image_node_sibling");
    }

    @Test
    void oneArchiveHoldsManyPages() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "作品");
        Long scannedId = insertScannedFile(imageLib.getId(), "vol01.cbz", "cbz");

        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key, archive_entry_name)
                VALUES (?, ?, 0, '001', '001.jpg')
                """, scannedId, nodeId);
        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key, archive_entry_name)
                VALUES (?, ?, 1, '002', '002.jpg')
                """, scannedId, nodeId);

        Integer pages = jdbc.queryForObject(
                "SELECT count(*) FROM image_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(pages).isEqualTo(2);
    }

    @Test
    void looseImageCannotBeRegisteredTwice() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "图集");
        Long scannedId = insertScannedFile(imageLib.getId(), "图集/001.jpg", "jpg");

        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 0, '001')
                """, scannedId, nodeId);

        // archive_entry_name 为 NULL 的两行也必须判为重复，同样靠 NULLS NOT DISTINCT
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 1, '001')
                """, scannedId, nodeId))
                .hasMessageContaining("uq_image_file_entry");
    }

    @Test
    void deletingScannedFileCascadesToImageFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "图集2");
        Long scannedId = insertScannedFile(imageLib.getId(), "图集2/001.jpg", "jpg");
        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 0, '001')
                """, scannedId, nodeId);

        jdbc.update("DELETE FROM scanned_file WHERE id = ?", scannedId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM image_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(remaining).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -8
```

Expected: 失败，`image_node` 表不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V8__image_domain.sql`：

```sql
-- ============================================================
-- 图片域。与视频域刻意不对称：
--   视频语义强 —— 「一部电影」「一季」是刮削与播放的天然单位，用语义模型；
--   图片组织高度个人化 —— 画师/年份/合集、作者/系列/单行本/卷、来源/主题，
--   深度各不相同，因此用任意深度的自由树。详见 spec 6.4。
--
-- 核心设计：「书」与「文件夹」不是互斥的节点类型，而是同一节点的两种能力。
-- ============================================================

CREATE TABLE image_node (
    id                      BIGSERIAL PRIMARY KEY,
    library_id              BIGINT      NOT NULL,
    domain                  VARCHAR(8)  NOT NULL DEFAULT 'IMAGE',
    parent_id               BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,

    -- 结构路径：'/1/17/93/'，由 id 组成。子树查询走前缀索引，面包屑直接解析。
    materialized_path       TEXT        NOT NULL,
    -- 顺序路径：'/1:1:1画师a/1:1:2卷/'，由各级 sort_key 组成。
    -- 存在的唯一理由是「强制书模式」要按目录深度优先顺序展开整棵子树的页，
    -- 而 id 路径的顺序是创建顺序，与名字顺序无关。
    sort_path               TEXT        NOT NULL,
    depth                   INT         NOT NULL,

    name                    TEXT        NOT NULL,
    sort_key                TEXT        NOT NULL,

    source_kind             VARCHAR(16) NOT NULL,
    -- ARCHIVE 节点指向压缩包本体（CBZ/ZIP），DIRECTORY 节点必须为空
    archive_scanned_file_id BIGINT      REFERENCES scanned_file (id) ON DELETE CASCADE,

    reading_mode            VARCHAR(16) NOT NULL DEFAULT 'AUTO',

    -- 计数字段。扫描结束时批量重算，不做实时递归统计。
    direct_page_count       INT         NOT NULL DEFAULT 0,
    child_node_count        INT         NOT NULL DEFAULT 0,
    total_page_count        INT         NOT NULL DEFAULT 0,

    cover_asset_id          BIGINT,
    title                   TEXT,
    summary                 TEXT,
    metadata                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    raw_metadata            JSONB,
    field_sources           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    locked_fields           TEXT[]      NOT NULL DEFAULT '{}',
    scrape_status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    scrape_source           VARCHAR(32),
    scrape_source_id        VARCHAR(64),

    status                  VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_image_node_source_kind CHECK (source_kind IN ('DIRECTORY', 'ARCHIVE')),
    CONSTRAINT ck_image_node_reading_mode CHECK (
        reading_mode IN ('AUTO', 'FORCE_BOOK', 'FORCE_FOLDER')),
    CONSTRAINT ck_image_node_status CHECK (status IN ('ACTIVE', 'MISSING')),
    CONSTRAINT ck_image_node_scrape_status CHECK (scrape_status IN (
        'NOT_APPLICABLE', 'PENDING', 'MATCHED', 'NO_MATCH', 'NEEDS_REVIEW', 'ERROR')),

    -- ARCHIVE 必须有压缩包本体，DIRECTORY 必须没有。等号两边都是布尔值，
    -- 一条 CHECK 同时表达了两个方向。
    CONSTRAINT ck_image_node_archive_ref CHECK (
        (source_kind = 'ARCHIVE') = (archive_scanned_file_id IS NOT NULL)),

    -- 域分区的数据库级强制，见 ADR-001
    CONSTRAINT ck_image_node_is_image CHECK (domain = 'IMAGE'),
    CONSTRAINT fk_image_node_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

-- 同一父节点下不允许重名。
-- NULLS NOT DISTINCT 是 PostgreSQL 15+ 的能力：默认情况下 NULL 互不相等，
-- 根节点（parent_id IS NULL）之间的重名根本不会被拦住。没有这个修饰词，
-- 顶层目录可以无限重复插入，find-or-create 每次扫描都会造一棵新树。
ALTER TABLE image_node
    ADD CONSTRAINT uq_image_node_sibling
        UNIQUE NULLS NOT DISTINCT (library_id, parent_id, name);

-- text_pattern_ops 让 LIKE '前缀%' 能走索引（默认排序规则下不行）
CREATE INDEX idx_image_node_subtree
    ON image_node (library_id, materialized_path text_pattern_ops);
CREATE INDEX idx_image_node_sortpath
    ON image_node (library_id, sort_path text_pattern_ops);
CREATE INDEX idx_image_node_parent ON image_node (parent_id, sort_key);
CREATE INDEX idx_image_node_archive ON image_node (archive_scanned_file_id);
-- 中文搜索主路径，见 spec 7.7
CREATE INDEX idx_image_node_name_trgm ON image_node USING gin (name gin_trgm_ops);

-- 语义层。页不建树节点 —— 一本 500 页的漫画若每页一个节点，树会被撑爆。
CREATE TABLE image_file (
    id                 BIGSERIAL PRIMARY KEY,
    scanned_file_id    BIGINT  NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    node_id            BIGINT  NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,

    -- 展示用页码。扫描结束时用窗口函数一条 SQL 重编号，见 Task 5。
    page_index         INT     NOT NULL DEFAULT 0,
    -- 排序真值。页码是它的产物，不是相反。
    sort_key           TEXT    NOT NULL,

    -- 非空表示来自压缩包内条目；为空表示散图目录里的独立文件
    archive_entry_name TEXT,

    width              INT,
    height             INT,
    format             VARCHAR(16),
    is_animated        BOOLEAN NOT NULL DEFAULT FALSE
);

-- 散图：一个 scanned_file 一行（entry 为 NULL）；CBZ：一个 scanned_file N 行。
-- 同样需要 NULLS NOT DISTINCT，否则散图可以被重复登记任意多次。
ALTER TABLE image_file
    ADD CONSTRAINT uq_image_file_entry
        UNIQUE NULLS NOT DISTINCT (scanned_file_id, archive_entry_name);

CREATE INDEX idx_image_file_node ON image_file (node_id, page_index);
CREATE INDEX idx_image_file_node_sort ON image_file (node_id, sort_key);
```

> **`UNIQUE NULLS NOT DISTINCT` 需要 PostgreSQL 15+。** 本项目锁定 `postgres:17`，可用。若迁移在此处报语法错误，说明连的是老版本数据库，检查 `compose.yaml` 的镜像标签，**不要把约束删掉了事**。

- [ ] **Step 4: 写枚举**

`src/main/java/com/mymedia/image/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "Image")
package com.mymedia.image;
```

`src/main/java/com/mymedia/image/ImageSourceKind.java`：

```java
package com.mymedia.image;

/**
 * 节点的内容来源。
 *
 * <p>只有两种：磁盘上的真实目录，或一个压缩包（CBZ/ZIP）。
 * 压缩包是树的叶子——它内部的条目是页，不是子节点。
 */
public enum ImageSourceKind { DIRECTORY, ARCHIVE }
```

`src/main/java/com/mymedia/image/ImageReadingMode.java`：

```java
package com.mymedia.image;

/**
 * 用户对节点阅读方式的覆盖。
 *
 * <p>系统的自动判定是「有直属图片就能读，有子节点就能浏览，两者皆有就都给」。
 * 但组织方式是高度个人化的，自动判定必然有猜错的时候，
 * 因此保留用户随时推翻的能力（spec §6.4）。
 */
public enum ImageReadingMode {
    /** 按 direct_page_count / child_node_count 自动判定。 */
    AUTO,
    /** 强制当作一本书：只给阅读入口，页 = 整棵子树的图片按目录顺序展开。 */
    FORCE_BOOK,
    /** 强制当作文件夹：只给浏览入口，即使有直属图片也不直接进阅读器。 */
    FORCE_FOLDER
}
```

`src/main/java/com/mymedia/image/ImageNodeStatus.java`：

```java
package com.mymedia.image;

public enum ImageNodeStatus { ACTIVE, MISSING }
```

- [ ] **Step 5: 写实体**

`src/main/java/com/mymedia/image/ImageNode.java`：

```java
package com.mymedia.image;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 图片库的树节点。
 *
 * <p><b>「书」与「文件夹」不是互斥类型，而是同一节点的两种能力</b>——
 * 一个目录既有散图又有子目录时，两个入口同时提供。这是 spec §6.4 的核心设计，
 * 也是与 Perfect Viewer 一致的交互模型。
 *
 * <p>节点带两条路径：{@code materializedPath} 由 id 组成，负责结构
 * （子树查询、面包屑）；{@code sortPath} 由各级排序键组成，负责顺序
 * （强制书模式下按目录顺序展开整棵子树的页）。移动子树时两条一起重写。
 */
@Entity
@Table(name = "image_node")
public class ImageNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    /** 恒为 "IMAGE"。复合外键把它钉死在所属库的 domain 上，见 ADR-001。 */
    @Column(nullable = false, length = 8, updatable = false)
    private String domain = "IMAGE";

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "materialized_path", nullable = false)
    private String materializedPath;

    @Column(name = "sort_path", nullable = false)
    private String sortPath;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 16)
    private ImageSourceKind sourceKind;

    @Column(name = "archive_scanned_file_id")
    private Long archiveScannedFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reading_mode", nullable = false, length = 16)
    private ImageReadingMode readingMode = ImageReadingMode.AUTO;

    @Column(name = "direct_page_count", nullable = false)
    private int directPageCount;

    @Column(name = "child_node_count", nullable = false)
    private int childNodeCount;

    @Column(name = "total_page_count", nullable = false)
    private int totalPageCount;

    @Column(name = "cover_asset_id")
    private Long coverAssetId;

    /** 刮削或用户编辑得到的标题。为空时展示 {@link #name}。 */
    @Column
    private String title;

    @Column
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ImageNodeStatus status = ImageNodeStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ImageNode() {
        // JPA 要求的无参构造器
    }

    private ImageNode(Long libraryId, Long parentId, String parentPath, String parentSortPath,
                      String name, ImageSourceKind sourceKind, Long archiveScannedFileId) {
        this.libraryId = libraryId;
        this.parentId = parentId;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);
        this.sourceKind = sourceKind;
        this.archiveScannedFileId = archiveScannedFileId;
        // 路径含自身 id，插入拿到 id 之后才能确定，先占位为父路径
        this.materializedPath = parentPath;
        this.sortPath = parentSortPath;
        this.depth = MaterializedPath.depthOf(parentPath);
    }

    static ImageNode directory(Long libraryId, Long parentId,
                               String parentPath, String parentSortPath, String name) {
        return new ImageNode(libraryId, parentId, parentPath, parentSortPath,
                name, ImageSourceKind.DIRECTORY, null);
    }

    static ImageNode archive(Long libraryId, Long parentId,
                             String parentPath, String parentSortPath,
                             String name, Long archiveScannedFileId) {
        return new ImageNode(libraryId, parentId, parentPath, parentSortPath,
                name, ImageSourceKind.ARCHIVE, archiveScannedFileId);
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getParentId() { return parentId; }
    public String getMaterializedPath() { return materializedPath; }
    public String getSortPath() { return sortPath; }
    public int getDepth() { return depth; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
    public String getTitle() { return title; }
    public ImageSourceKind getSourceKind() { return sourceKind; }
    public Long getArchiveScannedFileId() { return archiveScannedFileId; }
    public ImageReadingMode getReadingMode() { return readingMode; }
    public int getDirectPageCount() { return directPageCount; }
    public int getChildNodeCount() { return childNodeCount; }
    public int getTotalPageCount() { return totalPageCount; }
    public Long getCoverAssetId() { return coverAssetId; }
    public ImageNodeStatus getStatus() { return status; }

    /** 刮削到标题就用标题，否则回落到目录名——没有刮削也必须完全可用。 */
    public String getDisplayName() {
        return title == null || title.isBlank() ? name : title;
    }

    /**
     * 能否进入阅读器。
     *
     * <p>{@code FORCE_BOOK} 下恒为真：用户已经明确说了「这是一本书」，
     * 哪怕直属图片为零（页全在子目录里）也要给阅读入口。
     */
    public boolean isReadable() {
        return switch (readingMode) {
            case FORCE_BOOK -> true;
            case FORCE_FOLDER -> false;
            case AUTO -> directPageCount > 0;
        };
    }

    /** 能否进入子项网格。 */
    public boolean isBrowsable() {
        return switch (readingMode) {
            case FORCE_BOOK -> false;
            case FORCE_FOLDER -> true;
            case AUTO -> childNodeCount > 0;
        };
    }

    /** 插入拿到 id 之后补全两条路径。 */
    void finalizePaths(String parentPath, String parentSortPath) {
        this.materializedPath = MaterializedPath.childOf(parentPath, this.id);
        this.sortPath = parentSortPath + this.sortKey + "/";
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    /** 目录改名：结构路径不变（由 id 组成），顺序路径要跟着变。 */
    void rename(String newName, String parentSortPath) {
        this.name = newName;
        this.sortKey = NaturalSortKey.of(newName);
        this.sortPath = parentSortPath + this.sortKey + "/";
    }

    /** 目录移动到新父节点。子树的路径重写由 {@code ImageTreeRelocator} 一条 SQL 完成。 */
    void moveTo(Long newParentId, String newParentPath, String newParentSortPath) {
        this.parentId = newParentId;
        this.materializedPath = MaterializedPath.childOf(newParentPath, this.id);
        this.sortPath = newParentSortPath + this.sortKey + "/";
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    void overrideReadingMode(ImageReadingMode mode) {
        this.readingMode = mode;
    }

    void setCounts(int direct, int child, int total) {
        this.directPageCount = direct;
        this.childNodeCount = child;
        this.totalPageCount = total;
    }

    /** 由计划 05 的 preview 模块回填。 */
    void assignCover(Long assetId) {
        this.coverAssetId = assetId;
    }
}
```

`src/main/java/com/mymedia/image/ImageFile.java`：

```java
package com.mymedia.image;

import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一页。
 *
 * <p>散图目录：每张图一个 {@code scanned_file}，{@code archiveEntryName} 为 null。
 * <br>CBZ：一个 {@code scanned_file} 对应 N 行，各带自己的条目名。
 *
 * <p><b>页不建树节点。</b>
 */
@Entity
@Table(name = "image_file")
public class ImageFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 指向物理层。文件改名或移动时只有 {@code scanned_file.relative_path} 变化，
     * 本表与用户阅读进度完全不受影响 —— spec §6.1 分层设计的收益。
     */
    @Column(name = "scanned_file_id", nullable = false, updatable = false)
    private Long scannedFileId;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "archive_entry_name")
    private String archiveEntryName;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(length = 16)
    private String format;

    @Column(name = "is_animated", nullable = false)
    private boolean animated;

    protected ImageFile() {
    }

    /** 散图目录里的一张图。 */
    ImageFile(Long scannedFileId, Long nodeId, String fileName) {
        this.scannedFileId = scannedFileId;
        this.nodeId = nodeId;
        this.sortKey = NaturalSortKey.of(fileName);
    }

    /** 压缩包里的一个条目。 */
    ImageFile(Long scannedFileId, Long nodeId, String entryName, int pageIndex) {
        this.scannedFileId = scannedFileId;
        this.nodeId = nodeId;
        this.archiveEntryName = entryName;
        this.sortKey = NaturalSortKey.of(entryName);
        this.pageIndex = pageIndex;
    }

    public Long getId() { return id; }
    public Long getScannedFileId() { return scannedFileId; }
    public Long getNodeId() { return nodeId; }
    public int getPageIndex() { return pageIndex; }
    public String getSortKey() { return sortKey; }
    public String getArchiveEntryName() { return archiveEntryName; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getFormat() { return format; }
    public boolean isAnimated() { return animated; }

    /** 文件被移动到了另一个目录，页跟着换节点。 */
    void reattachTo(Long nodeId) {
        this.nodeId = nodeId;
    }

    /** 由计划 05 的 preview 模块探测后回填。 */
    void applyDimensions(Integer width, Integer height, String format, boolean animated) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.animated = animated;
    }
}
```

- [ ] **Step 6: 写仓储**

`src/main/java/com/mymedia/image/ImageNodeRepository.java`：

```java
package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ImageNodeRepository extends JpaRepository<ImageNode, Long> {

    Optional<ImageNode> findByLibraryIdAndParentIdAndName(Long libraryId, Long parentId, String name);

    Optional<ImageNode> findByLibraryIdAndParentIdIsNullAndName(Long libraryId, String name);

    Optional<ImageNode> findByArchiveScannedFileId(Long archiveScannedFileId);

    List<ImageNode> findByParentIdOrderBySortKey(Long parentId);

    List<ImageNode> findByLibraryIdAndParentIdIsNullOrderBySortKey(Long libraryId);

    List<ImageNode> findByLibraryId(Long libraryId);
}
```

`src/main/java/com/mymedia/image/ImageFileRepository.java`：

```java
package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ImageFileRepository extends JpaRepository<ImageFile, Long> {

    Optional<ImageFile> findByScannedFileIdAndArchiveEntryNameIsNull(Long scannedFileId);

    List<ImageFile> findByScannedFileId(Long scannedFileId);

    List<ImageFile> findByNodeIdOrderByPageIndex(Long nodeId);

    long countByNodeId(Long nodeId);

    void deleteByScannedFileId(Long scannedFileId);
}
```

- [ ] **Step 7: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V8__image_domain.sql src/main/java/com/mymedia/image src/test/java/com/mymedia/image
git commit -m "feat: 添加图片域表与实体

任意深度节点树，「书」与「文件夹」是同一节点的两种能力而非互斥类型。
兄弟重名用 UNIQUE NULLS NOT DISTINCT 拦住——默认 NULL 互不相等，
根节点之间的重名根本拦不住，find-or-create 每次扫描都会造一棵新树。
两条路径：id 路径管结构，排序键路径管顺序。"
```

Expected: `EXIT=0`，`Tests run: 10, Failures: 0`

---

## Task 2: 节点树索引器

把文件的相对路径变成一条节点链。视频域的 `VideoFolderIndexer` 做过类似的事，但图片域的树是**主模型**而非派生索引，因此多两件事：顺序路径的维护，以及压缩包叶子节点。

**Files:**
- Create: `src/main/java/com/mymedia/image/ImageNodeIndexer.java`
- Create: `src/main/java/com/mymedia/image/event/ImageNodeCreated.java`
- Test: `src/test/java/com/mymedia/image/ImageNodeIndexerTest.java`

**Interfaces:**
- Consumes: `MaterializedPath`、`NaturalSortKey`（计划 03 Task 1、2）、`ImageNode`、`ImageNodeRepository`（Task 1）、`LibraryService`（计划 01 Task 7）
- Produces:
  - `public record ImageNodeCreated(Long nodeId, Long libraryId, String name)`
  - `class ImageNodeIndexer`（package-private，Spring bean）
    - `ImageNode directoryNodeFor(Long libraryId, String relativePath)` — 返回该文件所在目录的节点，逐层 find-or-create
    - `ImageNode archiveNodeFor(Long libraryId, String relativePath, Long scannedFileId)` — 返回压缩包自身的叶子节点
    - `ImageNode findOrCreateChild(Long libraryId, ImageNode parent, String name)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageNodeIndexerTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImageNodeIndexerTest extends AbstractIntegrationTest {

    @Autowired
    ImageNodeIndexer indexer;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary imageLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/media/" + UUID.randomUUID());
    }

    private Long scannedFile(Long libraryId, String relativePath, String extension) {
        return jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, 100, now(), ?, 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, libraryId, relativePath, extension);
    }

    @Test
    void buildsOneNodePerPathSegment() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "画师A/2024/合集X/001.jpg");

        assertThat(leaf.getName()).isEqualTo("合集X");
        assertThat(leaf.getDepth()).isEqualTo(3);
        assertThat(leaf.getSourceKind()).isEqualTo(ImageSourceKind.DIRECTORY);
    }

    @Test
    void materializedPathContainsAncestorIds() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "a/b/c/001.jpg");

        // '/1/17/93/' —— 结构由 id 组成，改名不会让它变化
        assertThat(leaf.getMaterializedPath()).endsWith("/" + leaf.getId() + "/");
        assertThat(leaf.getMaterializedPath().split("/")).hasSize(4);   // 首元素为空串
    }

    @Test
    void sortPathContainsAncestorSortKeys() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "第2卷/第10话/001.jpg");

        // 顺序路径由排序键组成，用于强制书模式下按目录顺序展开子树
        assertThat(leaf.getSortPath()).startsWith("/");
        assertThat(leaf.getSortPath()).endsWith(leaf.getSortKey() + "/");
        assertThat(leaf.getSortPath().split("/")).hasSize(3);
    }

    @Test
    void reusesExistingNodesOnSecondCall() {
        MediaLibrary library = imageLibrary();

        ImageNode first = indexer.directoryNodeFor(library.getId(), "画师A/2024/001.jpg");
        ImageNode second = indexer.directoryNodeFor(library.getId(), "画师A/2024/002.jpg");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void fileAtLibraryRootGetsANodeNamedAfterTheLibrary() {
        MediaLibrary library = imageLibrary();

        ImageNode node = indexer.directoryNodeFor(library.getId(), "散图.jpg");

        // 库根下的散图也必须能读，因此建一个以库名命名的顶层节点收容它们
        assertThat(node.getName()).isEqualTo(library.getName());
        assertThat(node.getParentId()).isNull();
        assertThat(node.getDepth()).isEqualTo(1);
    }

    @Test
    void archiveBecomesALeafNodeNamedWithoutExtension() {
        MediaLibrary library = imageLibrary();
        Long scannedId = scannedFile(library.getId(), "漫画/某作品/vol01.cbz", "cbz");

        ImageNode node = indexer.archiveNodeFor(library.getId(), "漫画/某作品/vol01.cbz", scannedId);

        assertThat(node.getName()).isEqualTo("vol01");
        assertThat(node.getSourceKind()).isEqualTo(ImageSourceKind.ARCHIVE);
        assertThat(node.getArchiveScannedFileId()).isEqualTo(scannedId);
        assertThat(node.getDepth()).isEqualTo(3);
    }

    @Test
    void archiveNodeIsReusedOnRescan() {
        MediaLibrary library = imageLibrary();
        Long scannedId = scannedFile(library.getId(), "漫画/vol01.cbz", "cbz");

        ImageNode first = indexer.archiveNodeFor(library.getId(), "漫画/vol01.cbz", scannedId);
        ImageNode second = indexer.archiveNodeFor(library.getId(), "漫画/vol01.cbz", scannedId);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void siblingsAreCreatedUnderTheSameParent() {
        MediaLibrary library = imageLibrary();

        ImageNode a = indexer.directoryNodeFor(library.getId(), "根/子A/001.jpg");
        ImageNode b = indexer.directoryNodeFor(library.getId(), "根/子B/001.jpg");

        assertThat(a.getParentId()).isEqualTo(b.getParentId());
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageNodeIndexerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写事件**

`src/main/java/com/mymedia/image/event/ImageNodeCreated.java`：

```java
package com.mymedia.image.event;

/**
 * 新建了一个图片节点。
 *
 * <p>由 {@code metadata} 模块订阅去刮削、{@code preview} 模块订阅去生成封面。
 * {@code image} 模块不知道它们的存在。
 */
public record ImageNodeCreated(Long nodeId, Long libraryId, String name) {
}
```

- [ ] **Step 4: 实现索引器**

`src/main/java/com/mymedia/image/ImageNodeIndexer.java`：

```java
package com.mymedia.image;

import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.library.LibraryService;
import com.mymedia.shared.MaterializedPath;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把文件的相对路径变成一条节点链。
 *
 * <p>物化路径包含节点自身的 id，因此必须<b>先 INSERT 拿到 id、再补全路径</b>，
 * 这就是 {@link ImageNode#finalizePaths} 存在的原因。
 */
@Service
class ImageNodeIndexer {

    private final ImageNodeRepository nodeRepository;
    private final LibraryService libraryService;
    private final ApplicationEventPublisher events;

    ImageNodeIndexer(ImageNodeRepository nodeRepository,
                     LibraryService libraryService,
                     ApplicationEventPublisher events) {
        this.nodeRepository = nodeRepository;
        this.libraryService = libraryService;
        this.events = events;
    }

    /**
     * 返回该文件所在目录的节点，逐层 find-or-create。
     *
     * <p>文件直接躺在库根目录时没有目录可挂，此时建一个<b>以媒体库名命名的顶层节点</b>
     * 收容它们——库根下的散图同样必须可读，不能悄悄丢掉。
     */
    @Transactional
    ImageNode directoryNodeFor(Long libraryId, String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            String libraryName = libraryService.getById(libraryId).getName();
            return findOrCreateChild(libraryId, null, libraryName);
        }
        return walk(libraryId, relativePath.substring(0, lastSlash));
    }

    /**
     * 返回压缩包自身的叶子节点。
     *
     * <p>节点名去掉扩展名：{@code vol01.cbz} 显示为 {@code vol01}。
     */
    @Transactional
    ImageNode archiveNodeFor(Long libraryId, String relativePath, Long archiveScannedFileId) {
        var existing = nodeRepository.findByArchiveScannedFileId(archiveScannedFileId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int lastSlash = relativePath.lastIndexOf('/');
        ImageNode parent = lastSlash < 0 ? null : walk(libraryId, relativePath.substring(0, lastSlash));
        String fileName = relativePath.substring(lastSlash + 1);
        String nodeName = stripExtension(fileName);

        Long parentId = parent == null ? null : parent.getId();
        var sameName = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, nodeName)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, nodeName);
        if (sameName.isPresent()) {
            // 同目录下已有同名节点（例如同时存在 vol01/ 目录与 vol01.cbz），
            // 沿用既有节点，避免撞上兄弟唯一约束。
            return sameName.get();
        }

        String parentPath = parent == null ? MaterializedPath.rootPath() : parent.getMaterializedPath();
        String parentSortPath = parent == null ? MaterializedPath.rootPath() : parent.getSortPath();

        ImageNode created = nodeRepository.saveAndFlush(ImageNode.archive(
                libraryId, parentId, parentPath, parentSortPath, nodeName, archiveScannedFileId));
        created.finalizePaths(parentPath, parentSortPath);
        ImageNode saved = nodeRepository.saveAndFlush(created);
        events.publishEvent(new ImageNodeCreated(saved.getId(), libraryId, nodeName));
        return saved;
    }

    @Transactional
    ImageNode findOrCreateChild(Long libraryId, ImageNode parent, String name) {
        Long parentId = parent == null ? null : parent.getId();
        var existing = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);
        if (existing.isPresent()) {
            return existing.get();
        }

        String parentPath = parent == null ? MaterializedPath.rootPath() : parent.getMaterializedPath();
        String parentSortPath = parent == null ? MaterializedPath.rootPath() : parent.getSortPath();

        ImageNode created = nodeRepository.saveAndFlush(
                ImageNode.directory(libraryId, parentId, parentPath, parentSortPath, name));
        // 路径含自身 id，只能在拿到 id 之后补全
        created.finalizePaths(parentPath, parentSortPath);
        ImageNode saved = nodeRepository.saveAndFlush(created);
        events.publishEvent(new ImageNodeCreated(saved.getId(), libraryId, name));
        return saved;
    }

    private ImageNode walk(Long libraryId, String directoryPath) {
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = findOrCreateChild(libraryId, current, segment);
        }
        return current;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageNodeIndexerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageNodeIndexerTest.java
git commit -m "feat: 添加图片节点树索引器

路径含自身 id，必须先 INSERT 拿 id 再补全。
库根下的散图建一个以库名命名的顶层节点收容，不能悄悄丢掉。
压缩包是叶子节点，名字去掉扩展名。"
```

Expected: `EXIT=0`，`Tests run: 8, Failures: 0`

---

## Task 3: 语义层构建（实现扫描 SPI）

**Files:**
- Create: `src/main/java/com/mymedia/image/ImageContentBuilder.java`
- Create: `src/main/java/com/mymedia/image/ImageCatalogService.java`
- Test: `src/test/java/com/mymedia/image/ImageContentBuilderTest.java`

**Interfaces:**
- Consumes: `LibraryContentBuilder`、`MediaKind`、`ScannedFileDiscovered`、`ScannedFileVanished`（计划 02 Task 2、5、7）、`JobQueue`（计划 01 Task 10）、`ImageNodeIndexer`（Task 2）、各仓储（Task 1）
- Produces:
  - `ImageContentBuilder implements LibraryContentBuilder`（package-private，Spring bean）
  - `public class ImageCatalogService`
    - `public ImageNode getNode(Long nodeId)`
    - `public List<ImageNode> findRoots(Long libraryId)`
    - `public List<ImageFile> pagesOf(Long nodeId)`
    - `public ImageFile getFile(Long fileId)`
    - `public ImageNode setReadingMode(Long nodeId, ImageReadingMode mode)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageContentBuilderTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ImageContentBuilderTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /**
     * 扫描任务本身会再排出 ARCHIVE_INDEX 任务，而后者不可能落进同一轮抢占的批次里，
     * 所以要多跑一轮。这不是测试凑数，是任务队列的真实行为。
     */
    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        jobPoller.pollOnce();
    }

    @Test
    void looseImagesBecomePagesOfTheirDirectory() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("画师A/001.jpg");
        writeImage("画师A/002.jpg");

        scan(library.getId());

        List<ImageNode> roots = catalogService.findRoots(library.getId());
        assertThat(roots).extracting(ImageNode::getName).containsExactly("画师A");
        assertThat(catalogService.pagesOf(roots.getFirst().getId())).hasSize(2);
    }

    @Test
    void nestedDirectoriesBecomeATree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("画师A/2024/合集X/001.jpg");

        scan(library.getId());

        ImageNode artist = catalogService.findRoots(library.getId()).getFirst();
        ImageNode year = browseService.childNodes(library.getId(), artist.getId()).getFirst();
        ImageNode album = browseService.childNodes(library.getId(), year.getId()).getFirst();

        assertThat(artist.getName()).isEqualTo("画师A");
        assertThat(year.getName()).isEqualTo("2024");
        assertThat(album.getName()).isEqualTo("合集X");
        assertThat(album.getDepth()).isEqualTo(3);
    }

    @Test
    void archiveBecomesALeafNodeAndSchedulesAnIndexJob() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");

        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();          // 只跑扫描，先看索引任务有没有排出来

        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        ImageNode volume = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        assertThat(volume.getName()).isEqualTo("vol01");
        assertThat(volume.getSourceKind()).isEqualTo(ImageSourceKind.ARCHIVE);

        Integer queued = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'ARCHIVE_INDEX'", Integer.class);
        assertThat(queued).isEqualTo(1);
    }

    @Test
    void looseImageAtLibraryRootIsStillReadable() throws IOException {
        MediaLibrary library = libraryAtRoot();
        Files.writeString(root.resolve("散图.jpg"), "img");

        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(node.getName()).isEqualTo(library.getName());
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }

    @Test
    void rescanIsIdempotent() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");

        scan(library.getId());
        scan(library.getId());
        scan(library.getId());

        assertThat(catalogService.findRoots(library.getId())).hasSize(1);
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }

    @Test
    void renamingAFileKeepsTheSameImageFileRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/旧名.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Long pageId = catalogService.pagesOf(node.getId()).getFirst().getId();

        Files.move(root.resolve("图集/旧名.jpg"), root.resolve("图集/新名.jpg"));
        scan(library.getId());

        // 改名走物理层，语义层通过外键跟随 —— image_file 行不变
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getId)
                .containsExactly(pageId);
    }

    @Test
    void vanishedFileKeepsItsPageRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Files.delete(root.resolve("图集/001.jpg"));
        scan(library.getId());

        // 扫描绝不删除数据：外接盘没挂载也会让文件「消失」，
        // 删掉意味着用户的阅读进度、收藏、手工元数据一并蒸发。
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageContentBuilderTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写查询服务**

`src/main/java/com/mymedia/image/ImageCatalogService.java`：

```java
package com.mymedia.image;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code image} 模块对外暴露的节点与页查询能力。
 */
@Service
public class ImageCatalogService {

    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;

    ImageCatalogService(ImageNodeRepository nodeRepository, ImageFileRepository fileRepository) {
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional(readOnly = true)
    public ImageNode getNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("找不到图片节点 id=" + nodeId));
    }

    @Transactional(readOnly = true)
    public List<ImageNode> findRoots(Long libraryId) {
        return nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId);
    }

    /**
     * 节点的页。
     *
     * <p>{@code FORCE_BOOK} 下返回<b>整棵子树</b>的页，按（顺序路径, 页序）展开，
     * 也就是「章节顺序 + 页顺序」。这正是 {@code sort_path} 列存在的理由：
     * 结构路径由 id 组成，它的顺序是节点创建顺序，拿它排序会得到扫描时的偶然次序。
     */
    @Transactional(readOnly = true)
    public List<ImageFile> pagesOf(Long nodeId) {
        ImageNode node = getNode(nodeId);
        if (node.getReadingMode() == ImageReadingMode.FORCE_BOOK) {
            return fileRepository.findSubtreePages(
                    node.getLibraryId(), node.getMaterializedPath() + "%");
        }
        return fileRepository.findByNodeIdOrderByPageIndex(nodeId);
    }

    @Transactional(readOnly = true)
    public ImageFile getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("找不到图片文件 id=" + fileId));
    }

    /** 用户推翻自动判定。 */
    @Transactional
    public ImageNode setReadingMode(Long nodeId, ImageReadingMode mode) {
        ImageNode node = getNode(nodeId);
        node.overrideReadingMode(mode);
        return nodeRepository.save(node);
    }
}
```

在 `src/main/java/com/mymedia/image/ImageFileRepository.java` 中追加子树页查询（放在接口末尾，导入 `Query` 与 `Param`）：

```java
    @Query("""
            SELECT f FROM ImageFile f, ImageNode n
            WHERE f.nodeId = n.id
              AND n.libraryId = :libraryId
              AND n.materializedPath LIKE :pathPrefix
            ORDER BY n.sortPath, f.pageIndex
            """)
    List<ImageFile> findSubtreePages(@Param("libraryId") Long libraryId,
                                     @Param("pathPrefix") String pathPrefix);
```

对应的两条 import：

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 4: 实现 SPI**

`src/main/java/com/mymedia/image/ImageContentBuilder.java`：

```java
package com.mymedia.image;

import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryDomain;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把扫描发现的图片文件构建成节点树。
 *
 * <p>两条路径：
 * <ul>
 *   <li>散图 → 挂到所在目录的节点上，成为它的一页</li>
 *   <li>压缩包 → 自成一个 {@code ARCHIVE} 叶子节点，并排一个
 *       {@code ARCHIVE_INDEX} 任务去建页索引（见 Task 4）</li>
 * </ul>
 *
 * <p>整个过程<b>幂等</b>：重复扫描同一批文件不产生重复节点或重复页。
 */
@Component
class ImageContentBuilder implements LibraryContentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ImageContentBuilder.class);

    private final ImageNodeIndexer indexer;
    private final ImageFileRepository fileRepository;
    private final JobQueue jobQueue;

    ImageContentBuilder(ImageNodeIndexer indexer,
                        ImageFileRepository fileRepository,
                        JobQueue jobQueue) {
        this.indexer = indexer;
        this.fileRepository = fileRepository;
        this.jobQueue = jobQueue;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return domain == LibraryDomain.IMAGE;
    }

    @Override
    @Transactional
    public void onFileDiscovered(ScannedFileDiscovered event) {
        switch (event.kind()) {
            case IMAGE -> attachLooseImage(event);
            case ARCHIVE -> registerArchive(event);
            default -> { /* VIDEO / IGNORED 与图片域无关 */ }
        }
    }

    @Override
    @Transactional
    public void onFileVanished(ScannedFileVanished event) {
        // 语义层不做任何删除：物理层已标记 MISSING，节点与页仍在，
        // 用户的阅读进度、收藏、手工元数据全部保留。
        // 读取时由 ImagePageService 检查物理状态并返回明确错误。
        log.debug("图片文件不可用: {}", event.relativePath());
    }

    private void attachLooseImage(ScannedFileDiscovered event) {
        // 幂等保护：同一个物理文件只登记一次
        if (fileRepository.findByScannedFileIdAndArchiveEntryNameIsNull(
                event.scannedFileId()).isPresent()) {
            return;
        }
        ImageNode node = indexer.directoryNodeFor(event.libraryId(), event.relativePath());
        fileRepository.saveAndFlush(new ImageFile(
                event.scannedFileId(), node.getId(), fileNameOf(event.relativePath())));
    }

    private void registerArchive(ScannedFileDiscovered event) {
        ImageNode node = indexer.archiveNodeFor(
                event.libraryId(), event.relativePath(), event.scannedFileId());

        // dedup_key 保证同一个压缩包不会被重复排入索引任务
        jobQueue.enqueue(ArchiveIndexJobHandler.JOB_TYPE,
                "{\"scannedFileId\":" + event.scannedFileId()
                        + ",\"nodeId\":" + node.getId() + "}",
                "archive-index:" + event.scannedFileId());
        log.info("压缩包已登记为节点 id={} path={}", node.getId(), event.relativePath());
    }

    private static String fileNameOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
    }
}
```

- [ ] **Step 5: 建两个占位以便本任务独立通过**

本任务的测试用到 Task 4 的 `ArchiveIndexJobHandler.JOB_TYPE` 与 Task 7 的 `ImageBrowseService`。**先建最小占位，Task 4、7 会给出完整实现替换掉它们**：

`src/main/java/com/mymedia/image/ArchiveIndexJobHandler.java`（临时）：

```java
package com.mymedia.image;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;

// 临时占位，Task 4 会替换为完整实现
@Component
class ArchiveIndexJobHandler implements JobHandler {

    static final String JOB_TYPE = "ARCHIVE_INDEX";

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) {
        // Task 4 实现
    }
}
```

`src/main/java/com/mymedia/image/ImageBrowseService.java`（临时）：

```java
package com.mymedia.image;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 临时占位，Task 7 会替换为完整实现
@Service
public class ImageBrowseService {

    private final ImageNodeRepository nodeRepository;

    ImageBrowseService(ImageNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Transactional(readOnly = true)
    public List<ImageNode> childNodes(Long libraryId, Long nodeId) {
        return nodeId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId)
                : nodeRepository.findByParentIdOrderBySortKey(nodeId);
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageContentBuilderTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageContentBuilderTest.java
git commit -m "feat: 实现图片域语义层构建

散图挂到所在目录节点，压缩包自成叶子节点并排索引任务。
重扫幂等。文件消失只标记物理层，语义层与用户数据一行不删。"
```

Expected: `EXIT=0`，`Tests run: 7, Failures: 0`

---

## Task 4: 压缩包页索引

CBZ 本质是 ZIP。**绝不解压到磁盘**——ZIP 的中央目录区记录了每个条目的偏移量，可以直接定位并解压单个条目；整本解压意味着磁盘占用翻倍、清理逻辑、并发冲突三重代价。

**Files:**
- Modify: `pom.xml`（添加 commons-compress）
- Modify: `src/main/resources/application.yml`（添加 `mymedia.image.archive-charset`）
- Create: `src/main/java/com/mymedia/image/ArchivePage.java`
- Create: `src/main/java/com/mymedia/image/ImageArchiveReader.java`
- Modify: `src/main/java/com/mymedia/image/ArchiveIndexJobHandler.java`（替换 Task 3 的占位）
- Test: `src/test/java/com/mymedia/image/ImageArchiveReaderTest.java`
- Test: `src/test/java/com/mymedia/image/ArchiveIndexJobHandlerTest.java`

**Interfaces:**
- Consumes: `ScannedFileQueryService`、`ScannedFile`（计划 02 Task 1）、`LibraryService`（计划 01 Task 7）、`JobHandler`、`Job`（计划 01 Task 12）、`NaturalSortKey`（计划 03 Task 1）、`ImageNodeRepository`、`ImageFileRepository`（Task 1）
- Produces:
  - `record ArchivePage(String entryName, long sizeBytes)`（package-private）
  - `class ImageArchiveReader`（package-private，Spring bean）
    - `List<ArchivePage> listPages(Path archive) throws IOException` — 已过滤、已按自然序排序
    - `InputStream openEntry(Path archive, String entryName) throws IOException` — 关闭返回的流即关闭压缩包
  - `class ArchiveIndexJobHandler implements JobHandler`（package-private）— `static final String JOB_TYPE = "ARCHIVE_INDEX"`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageArchiveReaderTest.java`：

```java
package com.mymedia.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageArchiveReaderTest {

    @TempDir
    Path dir;

    private final ImageArchiveReader reader = new ImageArchiveReader(StandardCharsets.UTF_8);

    private Path archive(String name, String... entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    zip.write(("data-" + entry).getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void listsImageEntriesOnly() throws IOException {
        Path cbz = archive("a.cbz", "001.jpg", "002.png", "readme.txt", "ComicInfo.xml");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("001.jpg", "002.png");
    }

    @Test
    void sortsPagesNaturallyNotLexically() throws IOException {
        Path cbz = archive("b.cbz", "10.jpg", "2.jpg", "1.jpg", "20.jpg");

        // 字典序会排成 1, 10, 2, 20 —— 读者会从第 1 页直接跳到第 10 页
        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("1.jpg", "2.jpg", "10.jpg", "20.jpg");
    }

    @Test
    void keepsNestedEntriesInDirectoryOrder() throws IOException {
        Path cbz = archive("c.cbz", "第2章/001.jpg", "第1章/002.jpg", "第1章/001.jpg");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("第1章/001.jpg", "第1章/002.jpg", "第2章/001.jpg");
    }

    @Test
    void skipsDirectoriesAndJunkEntries() throws IOException {
        Path cbz = archive("d.cbz",
                "images/", "images/001.jpg", "__MACOSX/._001.jpg", ".DS_Store", "Thumbs.db");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("images/001.jpg");
    }

    @Test
    void readsASingleEntryWithoutExtractingTheWholeArchive() throws IOException {
        Path cbz = archive("e.cbz", "001.jpg", "002.jpg");

        try (InputStream in = reader.openEntry(cbz, "002.jpg")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("data-002.jpg");
        }

        // 目录里不应留下任何解压产物
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("e.cbz");
        }
    }

    @Test
    void closingTheStreamReleasesTheArchiveSoItCanBeDeleted() throws IOException {
        Path cbz = archive("f.cbz", "001.jpg");

        try (InputStream in = reader.openEntry(cbz, "001.jpg")) {
            in.readAllBytes();
        }

        // Windows 上被占用的文件删不掉 —— 这条断言就是文件句柄泄漏的探测器
        Files.delete(cbz);
        assertThat(Files.exists(cbz)).isFalse();
    }

    @Test
    void missingEntryFailsLoudly() throws IOException {
        Path cbz = archive("g.cbz", "001.jpg");

        assertThatThrownBy(() -> reader.openEntry(cbz, "999.jpg"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("999.jpg");
    }

    @Test
    void handlesArchiveWithNoImagesAtAll() throws IOException {
        Path cbz = archive("h.cbz", "readme.txt");

        assertThat(reader.listPages(cbz)).isEmpty();
    }
}
```

`src/test/java/com/mymedia/image/ArchiveIndexJobHandlerTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveIndexJobHandlerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private void scanAndIndex(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();      // 扫描
        jobPoller.pollOnce();      // 索引压缩包
    }

    private ImageNode archiveNodeOf(MediaLibrary library) {
        ImageNode top = catalogService.findRoots(library.getId()).getFirst();
        return browseService.childNodes(library.getId(), top.getId()).getFirst();
    }

    @Test
    void indexesEveryPageOfTheArchive() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg", "003.jpg");

        scanAndIndex(library.getId());

        List<ImageFile> pages = catalogService.pagesOf(archiveNodeOf(library).getId());
        assertThat(pages).extracting(ImageFile::getArchiveEntryName)
                .containsExactly("001.jpg", "002.jpg", "003.jpg");
    }

    @Test
    void pageIndexIsAssignedInNaturalOrder() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol02.cbz", "10.jpg", "2.jpg", "1.jpg");

        scanAndIndex(library.getId());

        assertThat(catalogService.pagesOf(archiveNodeOf(library).getId()))
                .extracting(ImageFile::getPageIndex, ImageFile::getArchiveEntryName)
                .containsExactly(
                        Tuple.tuple(0, "1.jpg"),
                        Tuple.tuple(1, "2.jpg"),
                        Tuple.tuple(2, "10.jpg"));
    }

    @Test
    void allPagesShareOneScannedFile() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol03.cbz", "001.jpg", "002.jpg");

        scanAndIndex(library.getId());

        List<ImageFile> pages = catalogService.pagesOf(archiveNodeOf(library).getId());
        // 一个压缩包是一个物理文件，N 个条目是 N 个语义页
        assertThat(pages).extracting(ImageFile::getScannedFileId)
                .containsOnly(pages.getFirst().getScannedFileId());
    }

    @Test
    void reindexingDoesNotDuplicatePages() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol04.cbz", "001.jpg", "002.jpg");

        scanAndIndex(library.getId());
        scanAndIndex(library.getId());

        assertThat(catalogService.pagesOf(archiveNodeOf(library).getId())).hasSize(2);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ImageArchiveReaderTest,ArchiveIndexJobHandlerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 添加依赖与配置**

在 `pom.xml` 的 `<dependencies>` 中追加（位置随意，放在 `postgresql` 之后即可）：

```xml
		<dependency>
			<groupId>org.apache.commons</groupId>
			<artifactId>commons-compress</artifactId>
			<version>1.28.0</version>
		</dependency>
```

> 版本号来自 `https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/maven-metadata.xml` 的 `<release>`，**不要凭记忆改**。它会传递引入 `commons-io:commons-io:2.20.0`（`ZipFile.builder()` 的父类 `AbstractStreamBuilder` 出自那里），不需要手写这条依赖。

在 `src/main/resources/application.yml` 的 `mymedia` 节点下追加：

```yaml
  image:
    # 压缩包条目名的「回退」编码，不是强制编码。
    # ZIP 通用位标记第 11 位为 1 的条目一律按 UTF-8 解，本设置根本不参与；
    # 只有没打这个标记的归档（Windows 中文压缩工具的典型产物）才用它。
    # 因此设成 GBK 不会破坏 UTF-8 归档 —— 这一点已实测验证。
    archive-charset: GBK
```

验证依赖能解析：

```bash
cd /d/MyMedia && mvn -B -ntp dependency:resolve > dep.log 2>&1; echo "EXIT=$?"; grep -E "commons-compress|commons-io" dep.log | head -5
rm -f dep.log
```

Expected: `EXIT=0`，输出含 `commons-compress:jar:1.28.0` 与 `commons-io:jar:2.20.0`。

- [ ] **Step 4: 实现压缩包读取器**

`src/main/java/com/mymedia/image/ArchivePage.java`：

```java
package com.mymedia.image;

/** 压缩包内的一个图片条目。{@code sizeBytes} 为 -1 表示归档未记录原始大小。 */
record ArchivePage(String entryName, long sizeBytes) {
}
```

`src/main/java/com/mymedia/image/ImageArchiveReader.java`：

```java
package com.mymedia.image;

import com.mymedia.shared.NaturalSortKey;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 压缩包（CBZ / ZIP）的随机访问读取。
 *
 * <p><b>绝不解压到磁盘。</b>ZIP 的中央目录区记录了每个条目的偏移量，
 * 可以直接定位并解压单个条目。
 *
 * <p>用 Commons Compress 而非 JDK 自带的 {@code java.util.zip.ZipFile}，
 * 理由是条目名编码：JDK 版本要求整个归档用同一种编码，遇到解不开的字节会抛异常；
 * Commons Compress 逐条目检查 ZIP 通用位标记第 11 位——打了标记的按 UTF-8 解，
 * 没打的才用构造时传入的<b>回退</b>编码。中文归档里两种条目混在一起是常态。
 */
@Component
class ImageArchiveReader {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "tiff", "tif");

    private final Charset fallbackCharset;

    ImageArchiveReader(@Value("${mymedia.image.archive-charset:GBK}") Charset fallbackCharset) {
        this.fallbackCharset = fallbackCharset;
    }

    /** 归档内的图片条目，已过滤、已按自然序排序。 */
    List<ArchivePage> listPages(Path archive) throws IOException {
        List<ArchivePage> pages = new ArrayList<>();
        try (ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (isPage(entry)) {
                    pages.add(new ArchivePage(entry.getName(), entry.getSize()));
                }
            }
        }
        // 归档内条目的物理顺序是打包顺序，不保证有意义；页序必须自己定，
        // 且必须是自然序 —— 字典序会把 1, 2, 10 排成 1, 10, 2。
        pages.sort(Comparator.comparing(page -> NaturalSortKey.of(page.entryName())));
        return pages;
    }

    /**
     * 打开单个条目。
     *
     * <p><b>返回的流关闭时会一并关闭压缩包</b>——否则文件句柄泄漏，
     * 在 Windows 上还会导致该压缩包无法被删除或改名。
     */
    InputStream openEntry(Path archive, String entryName) throws IOException {
        ZipFile zip = open(archive);
        try {
            ZipArchiveEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new FileNotFoundException("压缩包内没有条目: " + entryName + " @ " + archive);
            }
            InputStream delegate = zip.getInputStream(entry);
            return new FilterInputStream(delegate) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zip.close();
                    }
                }
            };
        } catch (IOException | RuntimeException e) {
            // 还没把 zip 的所有权交给调用方就出错了，这里必须自己收尾
            zip.close();
            throw e;
        }
    }

    private ZipFile open(Path archive) throws IOException {
        return ZipFile.builder()
                .setPath(archive)
                .setCharset(fallbackCharset)
                .get();
    }

    private static boolean isPage(ZipArchiveEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        String name = entry.getName();
        int lastSlash = name.lastIndexOf('/');
        String fileName = lastSlash < 0 ? name : name.substring(lastSlash + 1);

        // macOS 打包会塞进 __MACOSX/._xxx 资源分叉，Windows 会塞 Thumbs.db，
        // 前者甚至能通过扩展名检查，却不是页。
        if (name.startsWith("__MACOSX/") || fileName.startsWith(".")
                || fileName.equalsIgnoreCase("Thumbs.db")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 5: 实现索引任务**

`src/main/java/com/mymedia/image/ArchiveIndexJobHandler.java`（**替换 Task 3 的占位实现**）：

```java
package com.mymedia.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

/**
 * 为一个压缩包建立页索引。
 *
 * <p>为什么要建索引：不建的话每次翻页都得重新打开压缩包、重新读一遍中央目录区。
 * 一本 500 页的漫画从头读到尾就是 500 次目录区扫描。索引一次写进 {@code image_file}，
 * 之后翻页只按 id 定位条目。
 */
@Component
class ArchiveIndexJobHandler implements JobHandler {

    static final String JOB_TYPE = "ARCHIVE_INDEX";

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndexJobHandler.class);

    private final ObjectMapper objectMapper;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final ImageArchiveReader archiveReader;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;

    ArchiveIndexJobHandler(ObjectMapper objectMapper,
                           ScannedFileQueryService scannedFiles,
                           LibraryService libraryService,
                           ImageArchiveReader archiveReader,
                           ImageNodeRepository nodeRepository,
                           ImageFileRepository fileRepository) {
        this.objectMapper = objectMapper;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.archiveReader = archiveReader;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    @Transactional
    public void handle(Job job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        JsonNode idNode = payload.get("scannedFileId");
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "ARCHIVE_INDEX 任务缺少 scannedFileId: " + job.getPayload());
        }
        Long scannedFileId = idNode.asLong();

        ScannedFile scanned = scannedFiles.getById(scannedFileId);
        ImageNode node = nodeRepository.findByArchiveScannedFileId(scannedFileId)
                .orElseThrow(() -> new NotFoundException(
                        "压缩包没有对应节点 scannedFileId=" + scannedFileId));

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path archive = root.resolve(scanned.getRelativePath());

        List<ArchivePage> pages = archiveReader.listPages(archive);

        // 幂等：重建前先清掉旧行。页码由本次排序整体决定，
        // 增量比对不值得 —— 归档内容变了本来就该整本重排。
        fileRepository.deleteByScannedFileId(scannedFileId);
        fileRepository.flush();

        for (int i = 0; i < pages.size(); i++) {
            fileRepository.save(new ImageFile(
                    scannedFileId, node.getId(), pages.get(i).entryName(), i));
        }
        fileRepository.flush();

        log.info("压缩包索引完成 node={} pages={} path={}",
                node.getId(), pages.size(), scanned.getRelativePath());
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ImageArchiveReaderTest,ArchiveIndexJobHandlerTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add pom.xml src/main/resources/application.yml src/main/java/com/mymedia/image src/test/java/com/mymedia/image
git commit -m "feat: 添加 CBZ 页索引

绝不解压到磁盘，走 ZIP 中央目录区随机访问单条目。
用 Commons Compress 而非 JDK ZipFile：条目名编码逐条判定，
打了 UTF-8 标记的按 UTF-8 解，没打的才用回退编码——
中文归档里两种条目混在一起是常态，JDK 版本会直接抛异常。
返回的流关闭时一并关闭压缩包，否则 Windows 上该文件删不掉。"
```

Expected: `EXIT=0`，`ImageArchiveReaderTest` 8 个、`ArchiveIndexJobHandlerTest` 4 个全部通过

---

## Task 5: 扫描收尾——页序重编号与计数聚合

页码不能在发现每个文件时逐个分配：文件是一个个来的，第 3 页可能最后才被发现；而在中间插入一页会让后面所有页码全错。**页序在扫描结束时整体重算一次**，用窗口函数一条 SQL 完成。

计数字段同理。spec §6.4 写的是「扫描时增量维护，不做实时递归统计」——正确的读法是"不在每个文件事件里递归上溯"（那是 O(深度) 次 UPDATE × N 个文件），而不是"永远不算"。扫描结束时一次批量重算，既避免了递归，又不会让计数漂移。

**Files:**
- Create: `src/main/java/com/mymedia/image/ImageLibraryRecalculator.java`
- Create: `src/main/java/com/mymedia/image/ImageScanFinalizer.java`
- Test: `src/test/java/com/mymedia/image/ImageLibraryRecalculatorTest.java`

**Interfaces:**
- Consumes: `LibraryScanCompleted`（计划 02 Task 6）、`LibraryService`、`LibraryDomain`（计划 01 Task 7）、`JdbcTemplate`（Spring 自动配置）
- Produces:
  - `class ImageLibraryRecalculator`（package-private，Spring bean）— `void recalculate(Long libraryId)`
  - `class ImageScanFinalizer`（package-private，Spring bean）— `@EventListener void onScanCompleted(LibraryScanCompleted event)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageLibraryRecalculatorTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImageLibraryRecalculatorTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    @Autowired
    ImageScanFinalizer finalizer;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        jobPoller.pollOnce();
    }

    @Test
    void pageIndexFollowsNaturalOrderNotDiscoveryOrder() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/10.jpg");
        writeImage("图集/2.jpg");
        writeImage("图集/1.jpg");

        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getPageIndex)
                .containsExactly(0, 1, 2);
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getSortKey)
                .isSorted();
    }

    @Test
    void insertingAPageInTheMiddleShiftsTheLaterOnes() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");
        writeImage("图集/003.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Long thirdPageId = catalogService.pagesOf(node.getId()).getLast().getId();

        writeImage("图集/002.jpg");
        scan(library.getId());

        // 003 原本是第 1 页（0 基），插入 002 之后必须变成第 2 页。
        // 这就是页码不能在发现时逐个分配的原因。
        ImageFile third = catalogService.pagesOf(node.getId()).stream()
                .filter(page -> page.getId().equals(thirdPageId)).findFirst().orElseThrow();
        assertThat(third.getPageIndex()).isEqualTo(2);
    }

    @Test
    void countsAreMaintainedPerNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品/封面.jpg");
        writeImage("作品/第1话/001.jpg");
        writeImage("作品/第1话/002.jpg");
        writeImage("作品/第2话/001.jpg");

        scan(library.getId());

        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getDirectPageCount()).isEqualTo(1);
        assertThat(work.getChildNodeCount()).isEqualTo(2);
        // 既可读又可浏览 —— 「书」与「文件夹」不是互斥类型
        assertThat(work.isReadable()).isTrue();
        assertThat(work.isBrowsable()).isTrue();
    }

    @Test
    void totalPageCountAggregatesTheWholeSubtree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品/封面.jpg");
        writeImage("作品/第1话/001.jpg");
        writeImage("作品/第1话/002.jpg");
        writeImage("作品/第2话/001.jpg");

        scan(library.getId());

        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getTotalPageCount()).isEqualTo(4);

        ImageNode chapterOne = browseService.childNodes(library.getId(), work.getId()).getFirst();
        assertThat(chapterOne.getTotalPageCount()).isEqualTo(2);
    }

    @Test
    void siblingSubtreesDoNotLeakIntoEachOther() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品A/001.jpg");
        writeImage("作品B/001.jpg");
        writeImage("作品B/002.jpg");

        scan(library.getId());

        var roots = catalogService.findRoots(library.getId());
        assertThat(roots).extracting(ImageNode::getName).containsExactly("作品A", "作品B");
        assertThat(roots.get(0).getTotalPageCount()).isEqualTo(1);
        assertThat(roots.get(1).getTotalPageCount()).isEqualTo(2);
    }

    @Test
    void emptyDirectoryNodesArePruned() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("有内容/001.jpg");
        scan(library.getId());

        // 手工制造一个空壳节点——扫描过程中改名/移动会瞬时产生这种节点
        jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, '空壳', '空壳', 'DIRECTORY')
                """, library.getId());

        finalizer.onScanCompleted(new LibraryScanCompleted(library.getId(), 0, 0, 0, 0));

        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("有内容");
    }

    @Test
    void emptyArchiveNodeIsNeverPruned() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("占位/001.jpg");
        scan(library.getId());

        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, '未索引.cbz', 100, now(), 'cbz', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId());
        jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key,
                     source_kind, archive_scanned_file_id)
                VALUES (?, 'IMAGE', '/', '/', 0, '未索引', '未索引', 'ARCHIVE', ?)
                """, library.getId(), scannedId);

        finalizer.onScanCompleted(new LibraryScanCompleted(library.getId(), 0, 0, 0, 0));

        // 索引任务还没跑的压缩包页数就是 0，把它当空节点删掉等于把书弄丢了
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .contains("未索引");
    }

    @Test
    void recalculationSkipsVideoLibraries() {
        MediaLibrary videoLibrary = libraryService.create(
                "视频库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());

        // 视频库的扫描完成事件不应让图片域做任何事
        finalizer.onScanCompleted(new LibraryScanCompleted(videoLibrary.getId(), 0, 0, 0, 0));

        assertThat(catalogService.findRoots(videoLibrary.getId())).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageLibraryRecalculatorTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现重算器**

`src/main/java/com/mymedia/image/ImageLibraryRecalculator.java`：

```java
package com.mymedia.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次扫描结束后，整体重算一个图片库的页序与计数。
 *
 * <p>四步全部是<b>集合操作</b>，不在 Java 里循环行——几万张图的库也只是几条 SQL。
 */
@Service
class ImageLibraryRecalculator {

    private static final Logger log = LoggerFactory.getLogger(ImageLibraryRecalculator.class);

    /** 与 {@code mymedia.scan.max-depth} 同量级，用来给回收循环封顶。 */
    private static final int MAX_PRUNE_ROUNDS = 32;

    private final JdbcTemplate jdbc;

    ImageLibraryRecalculator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    void recalculate(Long libraryId) {
        int pruned = pruneEmptyDirectories(libraryId);
        int renumbered = renumberPages(libraryId);
        recountDirect(libraryId);
        recountSubtree(libraryId);
        log.debug("图片库重算完成 id={} 重编页={} 回收空节点={}", libraryId, renumbered, pruned);
    }

    /**
     * 页码重编号。
     *
     * <p>用窗口函数一条 SQL 完成：{@code row_number()} 按 {@code sort_key} 给同一节点下的
     * 页排序，序号直接写回 {@code page_index}。
     *
     * <p>为什么不在发现每个文件时分配页码：文件是一个个来的，中间插入一页会让
     * 后面所有页码全错，逐行修正是 O(n²) 次 UPDATE。
     *
     * <p>末尾的 {@code page_index <> rn - 1} 是为了跳过没变化的行——绝大多数扫描
     * 什么都没动，不应该产生任何写入。
     */
    private int renumberPages(Long libraryId) {
        return jdbc.update("""
                UPDATE image_file f
                SET page_index = t.rn - 1
                FROM (
                    SELECT f2.id,
                           row_number() OVER (PARTITION BY f2.node_id
                                              ORDER BY f2.sort_key, f2.id) AS rn
                    FROM image_file f2
                    JOIN image_node n ON n.id = f2.node_id
                    WHERE n.library_id = ?
                ) t
                WHERE f.id = t.id
                  AND f.page_index <> t.rn - 1
                """, libraryId);
    }

    /** 直属页数与直属子节点数。两条相关子查询，各自走已有索引。 */
    private void recountDirect(Long libraryId) {
        jdbc.update("""
                UPDATE image_node n
                SET direct_page_count = (SELECT count(*) FROM image_file f WHERE f.node_id = n.id),
                    child_node_count  = (SELECT count(*) FROM image_node c WHERE c.parent_id = n.id)
                WHERE n.library_id = ?
                """, libraryId);
    }

    /**
     * 子树页数聚合。
     *
     * <p>物化路径以斜杠收尾，因此 {@code LIKE '/1/17/%'} <b>不会</b>误匹配
     * {@code /1/170/}——这是物化路径最经典的 bug，收尾的那个斜杠就是防线。
     * 前缀本身也匹配自己（{@code '%'} 可以匹配空串），所以节点自己的直属页也被算进去。
     */
    private void recountSubtree(Long libraryId) {
        jdbc.update("""
                UPDATE image_node n
                SET total_page_count = COALESCE((
                        SELECT sum(d.direct_page_count)
                        FROM image_node d
                        WHERE d.library_id = n.library_id
                          AND d.materialized_path LIKE n.materialized_path || '%'), 0)
                WHERE n.library_id = ?
                """, libraryId);
    }

    /**
     * 回收既没有页也没有子节点的目录节点。
     *
     * <p>目录节点只在有文件需要它时才被创建，因此"零页零子节点"意味着内容已经
     * 全部移走或消失，节点是残留的空壳（改名与移动过程中会瞬时产生）。
     *
     * <p><b>只回收 DIRECTORY。</b>索引任务还没跑的 ARCHIVE 节点页数也是 0，
     * 把它当空节点删掉等于把整本书弄丢。
     *
     * <p>删掉一层可能让它的父节点也变空，所以要循环，用深度上限封顶。
     */
    private int pruneEmptyDirectories(Long libraryId) {
        int total = 0;
        for (int round = 0; round < MAX_PRUNE_ROUNDS; round++) {
            int deleted = jdbc.update("""
                    DELETE FROM image_node n
                    WHERE n.library_id = ?
                      AND n.source_kind = 'DIRECTORY'
                      AND NOT EXISTS (SELECT 1 FROM image_file f WHERE f.node_id = n.id)
                      AND NOT EXISTS (SELECT 1 FROM image_node c WHERE c.parent_id = n.id)
                    """, libraryId);
            total += deleted;
            if (deleted == 0) {
                return total;
            }
        }
        log.warn("空节点回收达到轮次上限 libraryId={}，树可能异常深", libraryId);
        return total;
    }
}
```

- [ ] **Step 4: 实现扫描收尾编排**

`src/main/java/com/mymedia/image/ImageScanFinalizer.java`：

```java
package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图片库扫描收尾的唯一入口。
 *
 * <p>把「先处理改名移动、再重算页序与计数」这个顺序固定在一个方法里，
 * 而不是靠两个监听器加 {@code @Order}——后者的顺序是隐式的，很容易在
 * 某次重构里被悄悄改掉。
 *
 * <p>用普通 {@code @EventListener}（同步、同事务）而非
 * {@code @ApplicationModuleListener}（异步）：扫描任务本身就跑在后台线程里，
 * 再异步一层只会让测试变成时序竞猜。
 */
@Component
class ImageScanFinalizer {

    private final LibraryService libraryService;
    private final ImageLibraryRecalculator recalculator;

    ImageScanFinalizer(LibraryService libraryService, ImageLibraryRecalculator recalculator) {
        this.libraryService = libraryService;
        this.recalculator = recalculator;
    }

    @EventListener
    void onScanCompleted(LibraryScanCompleted event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        recalculator.recalculate(event.libraryId());
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageLibraryRecalculatorTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageLibraryRecalculatorTest.java
git commit -m "feat: 添加图片库扫描收尾重算

页序用窗口函数一条 SQL 重编号——中间插入一页会让后面全错，
逐行修正是 O(n^2) 次 UPDATE。
子树计数走物化路径前缀，收尾斜杠保证 /1/17 不会匹配 /1/170。
空目录节点回收，但绝不回收还没索引的压缩包节点。"
```

Expected: `EXIT=0`，`Tests run: 8, Failures: 0`

---

## Task 6: 目录改名与移动

物理层已经保证了「文件改名只改 `relative_path`」。但图片域的树节点承载着阅读进度、收藏、阅读模式覆盖与刮削结果——**一个目录被改名或移动时，如果语义层建了新节点、丢下旧节点，这些用户数据就全没了**。这是 spec §6.1 那句"用户进度全部无损保留"在图片域的真正落地点，也是 `MaterializedPath.rewrite` 存在的理由。

**Files:**
- Create: `src/main/java/com/mymedia/image/ImageTreeRelocator.java`
- Modify: `src/main/java/com/mymedia/image/ImageNodeIndexer.java`（增加两个按目录路径查找/创建的方法）
- Modify: `src/main/java/com/mymedia/image/ImageScanFinalizer.java`（在重算之前先处理改名移动）
- Test: `src/test/java/com/mymedia/image/ImageTreeRelocatorTest.java`

**Interfaces:**
- Consumes: `ScannedFileRelocated`（计划 02 Task 6）、`MaterializedPath`（计划 03 Task 2）、`ImageNodeIndexer`（Task 2）、各仓储（Task 1）、`JdbcTemplate`
- Produces:
  - `class ImageTreeRelocator`（package-private，Spring bean）— `void applyPending(Long libraryId)`
  - `ImageNodeIndexer` 新增：`ImageNode directoryPathNode(Long libraryId, String directoryPath)`（查找或创建）、`ImageNode resolveDirectory(Long libraryId, String directoryPath)`（只查找，找不到返回 `null`）

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageTreeRelocatorTest.java`：

```java
package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTreeRelocatorTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + UUID.randomUUID());
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        jobPoller.pollOnce();
    }

    private ImageNode rootNamed(MediaLibrary library, String name) {
        return catalogService.findRoots(library.getId()).stream()
                .filter(node -> node.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("没有顶层节点: " + name));
    }

    @Test
    void renamingADirectoryKeepsTheSameNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作者A/001.jpg");
        scan(library.getId());

        ImageNode before = rootNamed(library, "作者A");
        Long pageId = catalogService.pagesOf(before.getId()).getFirst().getId();

        Files.move(root.resolve("作者A"), root.resolve("作者B"));
        scan(library.getId());

        ImageNode after = rootNamed(library, "作者B");
        // 节点 id 不变 —— 挂在它上面的阅读进度、收藏、阅读模式覆盖全部保住
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(catalogService.pagesOf(after.getId()))
                .extracting(ImageFile::getId).containsExactly(pageId);
    }

    @Test
    void renamingLeavesNoOrphanNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作者A/001.jpg");
        scan(library.getId());

        Files.move(root.resolve("作者A"), root.resolve("作者B"));
        scan(library.getId());

        // 扫描过程中会瞬时造出一个「作者B」空壳，收尾时必须只剩一个节点
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("作者B");
    }

    @Test
    void movingADirectoryRewritesTheWholeSubtree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("旧家/系列X/001.jpg");
        writeImage("旧家/系列X/子章/002.jpg");
        writeImage("新家/占位.jpg");
        scan(library.getId());

        ImageNode seriesBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "旧家").getId()).getFirst();
        ImageNode chapterBefore = browseService
                .childNodes(library.getId(), seriesBefore.getId()).getFirst();

        Files.move(root.resolve("旧家/系列X"), root.resolve("新家/系列X"));
        scan(library.getId());

        ImageNode seriesAfter = catalogService.getNode(seriesBefore.getId());
        ImageNode chapterAfter = catalogService.getNode(chapterBefore.getId());
        ImageNode newHome = rootNamed(library, "新家");

        // 节点 id 全部不变，但整棵子树的物化路径被一条 UPDATE 重写到新父之下
        assertThat(seriesAfter.getParentId()).isEqualTo(newHome.getId());
        assertThat(seriesAfter.getMaterializedPath()).startsWith(newHome.getMaterializedPath());
        assertThat(chapterAfter.getMaterializedPath()).startsWith(seriesAfter.getMaterializedPath());
        assertThat(chapterAfter.getDepth()).isEqualTo(seriesAfter.getDepth() + 1);
    }

    @Test
    void movingASingleFileOnlyReattachesThatPage() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("甲/001.jpg");
        writeImage("甲/002.jpg");
        writeImage("乙/003.jpg");
        scan(library.getId());

        ImageNode a = rootNamed(library, "甲");
        ImageNode b = rootNamed(library, "乙");

        Files.move(root.resolve("甲/001.jpg"), root.resolve("乙/001.jpg"));
        scan(library.getId());

        // 只搬走一个文件不是目录移动，甲必须原地不动
        assertThat(catalogService.getNode(a.getId()).getName()).isEqualTo("甲");
        assertThat(catalogService.pagesOf(a.getId())).hasSize(1);
        assertThat(catalogService.pagesOf(b.getId())).hasSize(2);
    }

    @Test
    void renamingAnArchiveKeepsItsNodeAndPages() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scan(library.getId());

        ImageNode comics = rootNamed(library, "漫画");
        ImageNode volumeBefore = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        List<Long> pageIdsBefore = catalogService.pagesOf(volumeBefore.getId())
                .stream().map(ImageFile::getId).toList();

        Files.move(root.resolve("漫画/vol01.cbz"), root.resolve("漫画/第01卷.cbz"));
        scan(library.getId());

        ImageNode volumeAfter = catalogService.getNode(volumeBefore.getId());
        assertThat(volumeAfter.getName()).isEqualTo("第01卷");
        assertThat(catalogService.pagesOf(volumeAfter.getId()))
                .extracting(ImageFile::getId)
                .containsExactlyElementsOf(pageIdsBefore);
    }

    @Test
    void movingAnArchiveToAnotherDirectoryMovesItsNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("待整理/vol01.cbz", "001.jpg");
        writeImage("已整理/占位.jpg");
        scan(library.getId());

        ImageNode volumeBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "待整理").getId()).getFirst();

        Files.move(root.resolve("待整理/vol01.cbz"), root.resolve("已整理/vol01.cbz"));
        scan(library.getId());

        ImageNode sorted = rootNamed(library, "已整理");
        assertThat(catalogService.getNode(volumeBefore.getId()).getParentId())
                .isEqualTo(sorted.getId());
    }

    @Test
    void movingTheParentAlsoCarriesNestedDirectories() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("顶层/中层/底层/001.jpg");
        scan(library.getId());

        ImageNode middleBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "顶层").getId()).getFirst();
        ImageNode bottomBefore = browseService
                .childNodes(library.getId(), middleBefore.getId()).getFirst();

        Files.move(root.resolve("顶层"), root.resolve("顶层改"));
        scan(library.getId());

        // 顶层改名，中层与底层的节点 id 与父子关系都不受影响
        assertThat(catalogService.getNode(middleBefore.getId()).getName()).isEqualTo("中层");
        assertThat(catalogService.getNode(bottomBefore.getId()).getParentId())
                .isEqualTo(middleBefore.getId());
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("顶层改");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageTreeRelocatorTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -8
```

- [ ] **Step 3: 给索引器加两个按目录路径工作的方法**

在 `src/main/java/com/mymedia/image/ImageNodeIndexer.java` 中，把私有的 `walk` 换成下面两个方法（`directoryNodeFor` 里的 `walk(...)` 调用改成 `directoryPathNode(...)`）：

```java
    /** 按目录路径查找或创建节点链。{@code directoryPath} 为空串时返回库根收容节点。 */
    @Transactional
    ImageNode directoryPathNode(Long libraryId, String directoryPath) {
        if (directoryPath.isEmpty()) {
            return findOrCreateChild(libraryId, null, libraryService.getById(libraryId).getName());
        }
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (!segment.isEmpty()) {
                current = findOrCreateChild(libraryId, current, segment);
            }
        }
        return current;
    }

    /**
     * 按目录路径只查找、不创建。任何一层不存在就返回 {@code null}。
     *
     * <p>改名与移动的处理需要区分"这个目录还在树里"和"它已经跟着祖先一起搬走了"，
     * 用 find-or-create 会把后者错认成前者并造出一个空壳。
     */
    @Transactional(readOnly = true)
    ImageNode resolveDirectory(Long libraryId, String directoryPath) {
        if (directoryPath.isEmpty()) {
            return nodeRepository.findByLibraryIdAndParentIdIsNullAndName(
                    libraryId, libraryService.getById(libraryId).getName()).orElse(null);
        }
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            var found = current == null
                    ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, segment)
                    : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, current.getId(), segment);
            if (found.isEmpty()) {
                return null;
            }
            current = found.get();
        }
        return current;
    }
```

同时把 `directoryNodeFor` 的实现改成：

```java
    @Transactional
    ImageNode directoryNodeFor(Long libraryId, String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return directoryPathNode(libraryId, lastSlash < 0 ? "" : relativePath.substring(0, lastSlash));
    }
```

以及 `archiveNodeFor` 里那句 `walk(libraryId, relativePath.substring(0, lastSlash))` 改成 `directoryPathNode(libraryId, relativePath.substring(0, lastSlash))`。

- [ ] **Step 4: 实现改名与移动**

`src/main/java/com/mymedia/image/ImageTreeRelocator.java`：

```java
package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.ScannedFileRelocated;
import com.mymedia.shared.MaterializedPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把物理层的「文件移动」翻译成语义层的「节点改名 / 子树移动」。
 *
 * <p>为什么需要它：物理层只知道一个个文件换了路径。若语义层照单全收地
 * 逐文件重挂，一个目录改名就会变成"建一个新节点、丢下一个旧节点"——
 * 旧节点上的阅读进度、收藏、阅读模式覆盖、刮削结果全部报废。
 *
 * <p>判定规则：把本轮的移动按（旧目录 → 新目录）分组。如果某组的文件数
 * <b>等于</b>旧目录节点下的全部文件数，说明整个目录搬走了，于是改名/移动节点本身；
 * 否则只是零散文件搬家，逐个重挂即可。
 *
 * <p>缓冲区是进程内的：应用在扫描中途崩溃会丢掉本轮判定，下次扫描退化成
 * 逐文件重挂。代价是一次目录级用户数据丢失，换来的是不引入额外的状态表——
 * 在单实例部署下这个取舍是划算的。
 */
@Service
class ImageTreeRelocator {

    private static final Logger log = LoggerFactory.getLogger(ImageTreeRelocator.class);

    private final Map<Long, List<Relocation>> pending = new ConcurrentHashMap<>();

    private final LibraryService libraryService;
    private final ImageNodeIndexer indexer;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final JdbcTemplate jdbc;

    ImageTreeRelocator(LibraryService libraryService,
                       ImageNodeIndexer indexer,
                       ImageNodeRepository nodeRepository,
                       ImageFileRepository fileRepository,
                       JdbcTemplate jdbc) {
        this.libraryService = libraryService;
        this.indexer = indexer;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(ScannedFileRelocated event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        pending.computeIfAbsent(event.libraryId(),
                        key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Relocation(event.scannedFileId(), event.oldPath(), event.newPath()));
    }

    @Transactional
    void applyPending(Long libraryId) {
        List<Relocation> batch = pending.remove(libraryId);
        if (batch == null || batch.isEmpty()) {
            return;
        }

        Map<DirectoryMove, List<Relocation>> groups = new LinkedHashMap<>();
        for (Relocation relocation : batch) {
            DirectoryMove move = new DirectoryMove(
                    directoryOf(relocation.oldPath()), directoryOf(relocation.newPath()));
            groups.computeIfAbsent(move, key -> new ArrayList<>()).add(relocation);
        }

        // 浅的目录先处理：父目录一旦整体搬走，它下面各层的组会自动落到正确位置，
        // 后续的 resolveDirectory 会返回 null，走逐文件重挂的兜底路径（此时是空操作）。
        List<DirectoryMove> ordered = groups.keySet().stream()
                .sorted(Comparator.comparingInt(move -> segmentCount(move.oldDirectory())))
                .toList();

        for (DirectoryMove move : ordered) {
            List<Relocation> moved = groups.get(move);
            if (!tryMoveWholeDirectory(libraryId, move, moved)) {
                reattachIndividually(libraryId, moved);
            }
        }
    }

    private boolean tryMoveWholeDirectory(Long libraryId, DirectoryMove move,
                                          List<Relocation> moved) {
        if (move.oldDirectory().equals(move.newDirectory())) {
            return false;      // 同目录内改名，节点结构不变（压缩包除外，交给兜底路径）
        }
        ImageNode oldNode = indexer.resolveDirectory(libraryId, move.oldDirectory());
        if (oldNode == null || oldNode.getSourceKind() != ImageSourceKind.DIRECTORY) {
            return false;
        }
        // 旧目录下的文件全都在本组里 → 整个目录搬走了
        if (fileRepository.countByNodeId(oldNode.getId()) != moved.size()) {
            return false;
        }
        return relocateNode(libraryId, oldNode,
                parentDirectoryOf(move.newDirectory()), lastSegmentOf(move.newDirectory()));
    }

    private void reattachIndividually(Long libraryId, List<Relocation> moved) {
        for (Relocation relocation : moved) {
            var archiveNode = nodeRepository.findByArchiveScannedFileId(relocation.scannedFileId());
            if (archiveNode.isPresent()) {
                // 压缩包换了位置或改了名：节点跟着走，页仍挂在它下面
                relocateNode(libraryId, archiveNode.get(),
                        directoryOf(relocation.newPath()),
                        stripExtension(fileNameOf(relocation.newPath())));
                continue;
            }
            ImageNode target = indexer.directoryNodeFor(libraryId, relocation.newPath());
            fileRepository.findByScannedFileId(relocation.scannedFileId())
                    .forEach(file -> file.reattachTo(target.getId()));
        }
    }

    /**
     * 把节点挪到新父之下并改成新名字，然后<b>一条 UPDATE 重写整棵子树的路径</b>。
     *
     * @return 是否完成；目标位置被真实内容占据时返回 {@code false}，由调用方兜底
     */
    private boolean relocateNode(Long libraryId, ImageNode node,
                                 String newParentDirectory, String newName) {
        ImageNode newParent = newParentDirectory.isEmpty()
                ? null
                : indexer.directoryPathNode(libraryId, newParentDirectory);
        Long newParentId = newParent == null ? null : newParent.getId();

        if (newParentId != null && newParentId.equals(node.getId())) {
            return false;      // 不能把节点挂到自己下面
        }

        if (!clearGhostAt(libraryId, newParentId, newName, node.getId())) {
            return false;
        }

        String oldPathPrefix = node.getMaterializedPath();
        String oldSortPrefix = node.getSortPath();
        String newParentPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getMaterializedPath();
        String newParentSortPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getSortPath();

        // 先改名（重算 sort_key），再移动（重算两条路径与深度）——
        // moveTo 用的是改名后的 sort_key，顺序不能反。
        node.rename(newName, newParentSortPath);
        node.moveTo(newParentId, newParentPath, newParentSortPath);
        nodeRepository.saveAndFlush(node);

        rewriteSubtree(libraryId, node.getId(),
                oldPathPrefix, node.getMaterializedPath(),
                oldSortPrefix, node.getSortPath());

        log.info("图片节点已跟随目录移动 id={} -> {}", node.getId(), node.getMaterializedPath());
        return true;
    }

    /**
     * 目标位置若已被一个空壳节点占着，先清掉。
     *
     * <p>空壳从哪来：扫描时先发布「发现新文件」再做改名配对，前者已经按新路径
     * 建好了节点，随后配对成功、新的 scanned_file 被删除，节点就空在那里了。
     * 不清掉它，改名过去会撞上兄弟唯一约束。
     *
     * @return 目标位置是否可用
     */
    private boolean clearGhostAt(Long libraryId, Long parentId, String name, Long movingNodeId) {
        var occupant = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);
        if (occupant.isEmpty() || occupant.get().getId().equals(movingNodeId)) {
            return true;
        }
        ImageNode ghost = occupant.get();
        boolean empty = fileRepository.countByNodeId(ghost.getId()) == 0
                && nodeRepository.findByParentIdOrderBySortKey(ghost.getId()).isEmpty();
        if (!empty) {
            log.debug("目标位置已有真实内容，放弃整目录移动: {}", name);
            return false;
        }
        nodeRepository.delete(ghost);
        nodeRepository.flush();
        return true;
    }

    /**
     * 子树路径重写：<b>一条前缀替换 UPDATE，不逐层递归</b>（spec §7.1 明确要求）。
     *
     * <p>深度直接在 SQL 里由新路径算出来：路径形如 {@code /1/17/93/}，
     * 深度就是斜杠数减一。
     *
     * <p>SET 子句右侧的 {@code materialized_path} 取的是<b>旧值</b>——
     * PostgreSQL 的 UPDATE 用行的原始值求值所有表达式，所以一条语句里
     * 既能读旧值又能写新值。
     */
    private void rewriteSubtree(Long libraryId, Long nodeId,
                                String oldPathPrefix, String newPathPrefix,
                                String oldSortPrefix, String newSortPrefix) {
        // 实体层的改动先落盘，否则下面这条原生 SQL 看不到它
        nodeRepository.flush();

        jdbc.update("""
                UPDATE image_node
                SET materialized_path = ? || substring(materialized_path FROM ?),
                    sort_path         = ? || substring(sort_path FROM ?),
                    depth = length(? || substring(materialized_path FROM ?))
                            - length(replace(? || substring(materialized_path FROM ?), '/', ''))
                            - 1
                WHERE library_id = ?
                  AND materialized_path LIKE ?
                  AND id <> ?
                """,
                newPathPrefix, oldPathPrefix.length() + 1,
                newSortPrefix, oldSortPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                libraryId,
                oldPathPrefix + "%",
                nodeId);
    }

    private static String directoryOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? "" : relativePath.substring(0, lastSlash);
    }

    private static String fileNameOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
    }

    private static String parentDirectoryOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? "" : directoryPath.substring(0, lastSlash);
    }

    private static String lastSegmentOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? directoryPath : directoryPath.substring(lastSlash + 1);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static int segmentCount(String directoryPath) {
        return directoryPath.isEmpty() ? 0 : directoryPath.split("/").length;
    }

    private record Relocation(Long scannedFileId, String oldPath, String newPath) {
    }

    private record DirectoryMove(String oldDirectory, String newDirectory) {
    }
}
```

- [ ] **Step 5: 接进扫描收尾**

修改 `src/main/java/com/mymedia/image/ImageScanFinalizer.java`，注入 relocator 并在重算之前调用：

```java
package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图片库扫描收尾的唯一入口。
 *
 * <p>顺序是有意义的：<b>先把改名与移动落实到节点树，再重算页序与计数</b>。
 * 反过来会先给一堆即将被回收的空壳节点算一遍数。
 *
 * <p>把顺序固定在一个方法里，而不是靠两个监听器加 {@code @Order}——
 * 后者的顺序是隐式的，很容易在某次重构里被悄悄改掉。
 */
@Component
class ImageScanFinalizer {

    private final LibraryService libraryService;
    private final ImageTreeRelocator relocator;
    private final ImageLibraryRecalculator recalculator;

    ImageScanFinalizer(LibraryService libraryService,
                       ImageTreeRelocator relocator,
                       ImageLibraryRecalculator recalculator) {
        this.libraryService = libraryService;
        this.relocator = relocator;
        this.recalculator = recalculator;
    }

    @EventListener
    void onScanCompleted(LibraryScanCompleted event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        relocator.applyPending(event.libraryId());
        recalculator.recalculate(event.libraryId());
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest='ImageTreeRelocatorTest,ImageLibraryRecalculatorTest' -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageTreeRelocatorTest.java
git commit -m "feat: 目录改名与移动跟随到节点树

物理层只知道文件换了路径；逐文件重挂会把目录改名变成
「建新节点、丢旧节点」，节点上的阅读进度与收藏全部报废。
按（旧目录→新目录）分组，整组搬完才判定为目录移动。
子树路径用一条前缀替换 UPDATE 重写，深度在 SQL 里由斜杠数算出。"
```

Expected: `EXIT=0`，`ImageTreeRelocatorTest` 7 个、`ImageLibraryRecalculatorTest` 8 个全部通过

---

## Task 7: 节点浏览 API 与阅读模式覆盖

**Files:**
- Modify: `src/main/java/com/mymedia/image/ImageBrowseService.java`（替换 Task 3 的占位）
- Create: `src/main/java/com/mymedia/image/web/ImageNodeDto.java`
- Create: `src/main/java/com/mymedia/image/web/ImageNodeController.java`
- Create: `src/main/java/com/mymedia/image/web/ImageBrowseController.java`
- Test: `src/test/java/com/mymedia/image/ImageBrowseServiceTest.java`

**Interfaces:**
- Consumes: `MaterializedPath`（计划 03 Task 2）、`ImageCatalogService`（Task 3）、`LibraryAccessService`（计划 01 Task 8）、`UserQueryService`（计划 01 Task 6）
- Produces:
  - `public class ImageBrowseService`
    - `public List<ImageNode> childNodes(Long libraryId, Long nodeId)`
    - `public List<ImageNode> breadcrumb(Long nodeId)`
    - `public ImageNode getNode(Long nodeId)`
  - `GET /api/image/nodes`、`GET /api/image/nodes/{id}`、`PUT /api/image/nodes/{id}/reading-mode`、`GET /api/image/browse?libraryId=&nodeId=`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageBrowseServiceTest.java`：

```java
package com.mymedia.image;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImageBrowseServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    private String username;

    private MediaLibrary setUpLibrary() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());
        return library;
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    @Test
    void breadcrumbResolvesAncestorsWithoutRecursiveQuery() throws IOException {
        writeImage("一层/二层/三层/001.jpg");
        MediaLibrary library = setUpLibrary();

        ImageNode l1 = catalogService.findRoots(library.getId()).getFirst();
        ImageNode l2 = browseService.childNodes(library.getId(), l1.getId()).getFirst();
        ImageNode l3 = browseService.childNodes(library.getId(), l2.getId()).getFirst();

        assertThat(browseService.breadcrumb(l3.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("一层", "二层", "三层");
    }

    @Test
    void childNodesAreSortedNaturally() throws IOException {
        writeImage("第10卷/001.jpg");
        writeImage("第2卷/001.jpg");
        writeImage("第1卷/001.jpg");
        MediaLibrary library = setUpLibrary();

        assertThat(browseService.childNodes(library.getId(), null))
                .extracting(ImageNode::getName)
                .containsExactly("第1卷", "第2卷", "第10卷");
    }

    @Test
    void browseEndpointReturnsBreadcrumbAndChildren() throws Exception {
        writeImage("作者/系列/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode author = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/browse")
                        .param("libraryId", String.valueOf(library.getId()))
                        .param("nodeId", String.valueOf(author.getId()))
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breadcrumb[0].name").value("作者"))
                .andExpect(jsonPath("$.nodes[0].name").value("系列"));
    }

    @Test
    void nodeDetailExposesBothCapabilities() throws Exception {
        writeImage("混合/封面.jpg");
        writeImage("混合/子目录/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode mixed = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/nodes/" + mixed.getId())
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true))
                .andExpect(jsonPath("$.browsable").value(true))
                .andExpect(jsonPath("$.directPageCount").value(1))
                .andExpect(jsonPath("$.childNodeCount").value(1));
    }

    @Test
    void forceFolderHidesTheReadingEntry() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(put("/api/image/nodes/" + node.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FORCE_FOLDER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false))
                .andExpect(jsonPath("$.browsable").value(true));
    }

    @Test
    void forceBookGivesAReadingEntryEvenWithoutDirectPages() throws Exception {
        writeImage("作品/第1话/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getDirectPageCount()).isZero();

        mockMvc.perform(put("/api/image/nodes/" + work.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FORCE_BOOK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true))
                .andExpect(jsonPath("$.browsable").value(false));
    }

    @Test
    void rejectsUnknownReadingMode() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(put("/api/image/nodes/" + node.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"随便看看\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        // 404 而非 403：不向无权访问者泄露资源是否存在
        mockMvc.perform(get("/api/image/nodes/" + node.getId()).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/nodes/" + node.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void topLevelListingOnlyCoversAccessibleImageLibraries() throws Exception {
        writeImage("可见/001.jpg");
        MediaLibrary visible = setUpLibrary();

        MediaLibrary hidden = libraryService.create(
                "隐藏" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(hidden.getId());
        jobPoller.pollOnce();

        mockMvc.perform(get("/api/image/nodes").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(
                        catalogService.findRoots(visible.getId()).size()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageBrowseServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现浏览服务**

`src/main/java/com/mymedia/image/ImageBrowseService.java`（**替换 Task 3 的占位实现**）：

```java
package com.mymedia.image;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 图片库的树浏览。
 *
 * <p>与视频域不同，这里的树是<b>主浏览方式</b>而非次要视图——
 * 图片内容的组织方式高度个人化，系统不替用户决定层级。
 */
@Service
public class ImageBrowseService {

    private final ImageNodeRepository nodeRepository;

    ImageBrowseService(ImageNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Transactional(readOnly = true)
    public List<ImageNode> childNodes(Long libraryId, Long nodeId) {
        return nodeId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId)
                : nodeRepository.findByParentIdOrderBySortKey(nodeId);
    }

    @Transactional(readOnly = true)
    public ImageNode getNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("找不到图片节点 id=" + nodeId));
    }

    /**
     * 面包屑导航。
     *
     * <p>直接从物化路径解析出全部祖先 id，<b>一次查询搞定，不需要递归</b>——
     * 这正是存物化路径的主要收益。深度 10 的树也只有一次 {@code IN} 查询。
     */
    @Transactional(readOnly = true)
    public List<ImageNode> breadcrumb(Long nodeId) {
        ImageNode node = getNode(nodeId);
        List<Long> ancestorIds = MaterializedPath.ancestorIds(node.getMaterializedPath());
        List<ImageNode> nodes = nodeRepository.findAllById(ancestorIds);
        // findAllById 不保证顺序，按物化路径中的顺序重排
        return ancestorIds.stream()
                .map(id -> nodes.stream()
                        .filter(candidate -> candidate.getId().equals(id))
                        .findFirst().orElseThrow())
                .toList();
    }
}
```

- [ ] **Step 4: 写 DTO**

`src/main/java/com/mymedia/image/web/ImageNodeDto.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;

import java.util.List;

public final class ImageNodeDto {

    private ImageNodeDto() {
    }

    /**
     * 节点摘要。
     *
     * <p>{@code readable} 与 {@code browsable} 是<b>两个独立的布尔值</b>，
     * 不是一个 type 字段——一个目录既有散图又有子目录时两者同时为真，
     * 前端据此同时渲染「阅读」与「进入」两个入口。
     */
    public record NodeSummary(
            Long id,
            String name,
            String displayName,
            int depth,
            String sourceKind,
            String readingMode,
            int directPageCount,
            int childNodeCount,
            int totalPageCount,
            boolean readable,
            boolean browsable) {

        public static NodeSummary from(ImageNode node) {
            return new NodeSummary(
                    node.getId(), node.getName(), node.getDisplayName(), node.getDepth(),
                    node.getSourceKind().name(), node.getReadingMode().name(),
                    node.getDirectPageCount(), node.getChildNodeCount(), node.getTotalPageCount(),
                    node.isReadable(), node.isBrowsable());
        }
    }

    public record PageSummary(Long id, int pageIndex, Integer width, Integer height) {

        public static PageSummary from(ImageFile file) {
            return new PageSummary(file.getId(), file.getPageIndex(),
                    file.getWidth(), file.getHeight());
        }
    }

    public record BrowseResponse(List<NodeSummary> breadcrumb, List<NodeSummary> nodes) {
    }

    public record ReadingModeRequest(String mode) {
    }
}
```

- [ ] **Step 5: 写控制器**

`src/main/java/com/mymedia/image/web/ImageNodeController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImageReadingMode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/image/nodes")
class ImageNodeController {

    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageNodeController(ImageCatalogService catalogService,
                        LibraryAccessService accessService,
                        UserQueryService userQueryService) {
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    /** 用户可访问的全部图片库的顶层节点。 */
    @GetMapping
    List<ImageNodeDto.NodeSummary> topLevel(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.IMAGE)
                .map(MediaLibrary::getId)
                .flatMap(libraryId -> catalogService.findRoots(libraryId).stream())
                .map(ImageNodeDto.NodeSummary::from)
                .toList();
    }

    @GetMapping("/{id}")
    ImageNodeDto.NodeSummary detail(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        return ImageNodeDto.NodeSummary.from(requireAccessible(principal, id));
    }

    @GetMapping("/{id}/pages")
    List<ImageNodeDto.PageSummary> pages(@AuthenticationPrincipal UserDetails principal,
                                         @PathVariable Long id) {
        requireAccessible(principal, id);
        return catalogService.pagesOf(id).stream()
                .map(ImageNodeDto.PageSummary::from)
                .toList();
    }

    /** 用户推翻自动判定。 */
    @PutMapping("/{id}/reading-mode")
    ImageNodeDto.NodeSummary setReadingMode(@AuthenticationPrincipal UserDetails principal,
                                            @PathVariable Long id,
                                            @RequestBody ImageNodeDto.ReadingModeRequest request) {
        requireAccessible(principal, id);
        return ImageNodeDto.NodeSummary.from(
                catalogService.setReadingMode(id, parseMode(request.mode())));
    }

    private static ImageReadingMode parseMode(String raw) {
        try {
            return ImageReadingMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "阅读模式只能是 AUTO / FORCE_BOOK / FORCE_FOLDER，收到: " + raw);
        }
    }

    private ImageNode requireAccessible(UserDetails principal, Long nodeId) {
        ImageNode node = catalogService.getNode(nodeId);
        if (!accessService.canAccess(currentUserId(principal), node.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
        return node;
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

`src/main/java/com/mymedia/image/web/ImageBrowseController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageBrowseService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/image/browse")
class ImageBrowseController {

    private final ImageBrowseService browseService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageBrowseController(ImageBrowseService browseService,
                          LibraryAccessService accessService,
                          UserQueryService userQueryService) {
        this.browseService = browseService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    ImageNodeDto.BrowseResponse browse(@AuthenticationPrincipal UserDetails principal,
                                       @RequestParam Long libraryId,
                                       @RequestParam(required = false) Long nodeId) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }

        List<ImageNodeDto.NodeSummary> breadcrumb = nodeId == null
                ? List.of()
                : browseService.breadcrumb(nodeId).stream()
                        .map(ImageNodeDto.NodeSummary::from).toList();

        return new ImageNodeDto.BrowseResponse(
                breadcrumb,
                browseService.childNodes(libraryId, nodeId).stream()
                        .map(ImageNodeDto.NodeSummary::from).toList());
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageBrowseServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageBrowseServiceTest.java
git commit -m "feat: 添加图片节点浏览 API 与阅读模式覆盖

readable 与 browsable 是两个独立布尔值而非一个 type 字段——
既有散图又有子目录的节点两个入口同时给。
面包屑直接解析物化路径，一次查询搞定。"
```

Expected: `EXIT=0`，`Tests run: 10, Failures: 0`

---

## Task 8: 分页阅读与单页流式输出

**Files:**
- Create: `src/main/java/com/mymedia/image/ImagePageService.java`
- Create: `src/main/java/com/mymedia/image/web/ImagePageController.java`
- Test: `src/test/java/com/mymedia/image/ImagePageControllerTest.java`

**Interfaces:**
- Consumes: `ImageCatalogService`（Task 3）、`ImageArchiveReader`（Task 4）、`ScannedFileQueryService`、`ScannedFileStatus`（计划 02 Task 1）、`LibraryService`、`LibraryAccessService`（计划 01）
- Produces:
  - `public class ImagePageService`
    - `public PageTarget locate(Long userId, Long fileId)`
    - `public InputStream open(PageTarget target) throws IOException`
    - `public record PageTarget(Path path, String archiveEntryName, long sizeBytes, String etag, Instant lastModified, String contentType)`
  - `GET /api/image/page/{fileId}`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImagePageControllerTest.java`：

```java
package com.mymedia.image;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImagePageControllerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    private String username;
    private MediaLibrary library;

    private void writeImage(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private void scanAndGrant() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());
    }

    private void rescan() {
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();
    }

    @Test
    void servesALooseImage() throws Exception {
        writeImage("图集/001.jpg", "HELLO-PAGE");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        MvcResult result = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("HELLO-PAGE");
    }

    @Test
    void servesAPageFromInsideAnArchiveWithoutExtracting() throws Exception {
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scanAndGrant();
        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        ImageNode volume = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        Long secondPage = catalogService.pagesOf(volume.getId()).get(1).getId();

        MvcResult result = mockMvc.perform(get("/api/image/page/" + secondPage)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("page-002.jpg");
    }

    @Test
    void responseCarriesEtagAndCacheControl() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(username, "pw")))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void repeatRequestWithMatchingEtagGets304() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        String etag = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw")))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // 阅读器来回翻页会反复请求同一页，304 让它一个字节都不用再传
        MvcResult cached = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andReturn();
        assertThat(cached.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void pageListIsOrderedByPageIndex() throws Exception {
        writeImage("图集/10.jpg", "a");
        writeImage("图集/2.jpg", "b");
        writeImage("图集/1.jpg", "c");
        scanAndGrant();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/nodes/" + nodeId + "/pages")
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageIndex").value(0))
                .andExpect(jsonPath("$[1].pageIndex").value(1))
                .andExpect(jsonPath("$[2].pageIndex").value(2));
    }

    @Test
    void forceBookModeReadsTheWholeSubtreeInChapterOrder() throws Exception {
        writeImage("作品/第1话/001.jpg", "c1p1");
        writeImage("作品/第1话/002.jpg", "c1p2");
        writeImage("作品/第2话/001.jpg", "c2p1");
        scanAndGrant();
        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        catalogService.setReadingMode(work.getId(), ImageReadingMode.FORCE_BOOK);

        // 章节顺序 + 页顺序，靠的是 sort_path 而不是节点 id
        assertThat(catalogService.pagesOf(work.getId())).hasSize(3);

        mockMvc.perform(get("/api/image/nodes/" + work.getId() + "/pages")
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void missingFileGivesNotFoundInsteadOfServerError() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        Files.delete(root.resolve("图集/001.jpg"));
        rescan();

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/page/" + pageId))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImagePageControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现定位与开流服务**

`src/main/java/com/mymedia/image/ImagePageService.java`：

```java
package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * 单页的定位、鉴权与开流。
 *
 * <p>散图与压缩包内页走<b>同一个端点</b>，对前端完全透明——
 * 阅读器不需要知道这本书是一个目录还是一个 CBZ。
 */
@Service
public class ImagePageService {

    private final ImageCatalogService catalogService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final LibraryAccessService accessService;
    private final ImageArchiveReader archiveReader;

    ImagePageService(ImageCatalogService catalogService,
                     ScannedFileQueryService scannedFiles,
                     LibraryService libraryService,
                     LibraryAccessService accessService,
                     ImageArchiveReader archiveReader) {
        this.catalogService = catalogService;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.accessService = accessService;
        this.archiveReader = archiveReader;
    }

    /**
     * 定位物理文件并校验访问权。
     *
     * <p>无权访问一律抛 {@link NotFoundException} 而非权限异常——
     * 返回 403 会泄露「这个 id 确实存在」。
     */
    @Transactional(readOnly = true)
    public PageTarget locate(Long userId, Long fileId) {
        ImageFile page = catalogService.getFile(fileId);
        ScannedFile scanned = scannedFiles.getById(page.getScannedFileId());

        if (!accessService.canAccess(userId, scanned.getLibraryId())) {
            throw new NotFoundException("找不到图片 id=" + fileId);
        }
        if (scanned.getStatus() == ScannedFileStatus.MISSING) {
            throw new NotFoundException(
                    "文件当前不可用（可能所在磁盘未挂载）: " + scanned.getRelativePath());
        }

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path path = root.resolve(scanned.getRelativePath());

        // ETag 由页 id + 物理文件大小 + 修改时间构成：底层文件一变 ETag 必变，
        // 客户端缓存的旧页就会被判为过期。
        String etag = "\"" + page.getId() + "-" + scanned.getSizeBytes()
                + "-" + scanned.getMtime().toEpochMilli() + "\"";

        String nameForType = page.getArchiveEntryName() == null
                ? scanned.getRelativePath()
                : page.getArchiveEntryName();

        return new PageTarget(path, page.getArchiveEntryName(),
                page.getArchiveEntryName() == null ? scanned.getSizeBytes() : -1,
                etag, scanned.getMtime(), contentTypeOf(nameForType));
    }

    /** 打开页的字节流。调用方负责关闭。 */
    public InputStream open(PageTarget target) throws IOException {
        return target.archiveEntryName() == null
                ? Files.newInputStream(target.path())
                : archiveReader.openEntry(target.path(), target.archiveEntryName());
    }

    private static String contentTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /** {@code archiveEntryName} 为 null 表示散图；{@code sizeBytes} 为 -1 表示大小未知。 */
    public record PageTarget(
            Path path,
            String archiveEntryName,
            long sizeBytes,
            String etag,
            Instant lastModified,
            String contentType) {
    }
}
```

- [ ] **Step 4: 实现端点**

`src/main/java/com/mymedia/image/web/ImagePageController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImagePageService;
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

@RestController
@RequestMapping("/api/image/page")
class ImagePageController {

    /** 页的字节内容只会随底层文件变化而变，而那会改掉 ETag，所以可以放心缓存一天。 */
    private static final String CACHE_CONTROL = "private, max-age=86400";

    private final ImagePageService pageService;
    private final UserQueryService userQueryService;

    ImagePageController(ImagePageService pageService, UserQueryService userQueryService) {
        this.pageService = pageService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{fileId}")
    ResponseEntity<StreamingResponseBody> page(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        ImagePageService.PageTarget target = pageService.locate(userId, fileId);

        // 阅读器来回翻页会反复请求同一页；ETag 命中就一个字节都不用再传。
        // 这里手工比对而不靠 ShallowEtagHeaderFilter —— 后者要把整页读进内存
        // 才能算出摘要，正好抵消了流式输出的意义。
        if (target.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, target.etag())
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.ETAG, target.etag())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_TYPE, target.contentType());
        if (target.sizeBytes() >= 0) {
            response = response.contentLength(target.sizeBytes());
        }

        return response.body(writer(target));
    }

    /**
     * 流式写出。
     *
     * <p>压缩包内页走的是 {@code ZipFile.getInputStream}——按需解压单个条目，
     * <b>不解压整个归档</b>。流关闭时压缩包一并关闭。
     *
     * <p>虚拟线程承载这段阻塞 I/O，不占用平台线程。
     */
    private StreamingResponseBody writer(ImagePageService.PageTarget target) {
        return (OutputStream out) -> {
            try (InputStream in = pageService.open(target)) {
                in.transferTo(out);
            } catch (IOException e) {
                // 用户快速翻页会中断上一页的连接，这是正常行为不是错误。
                // 静默结束，避免日志被刷屏。
            }
        };
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImagePageControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImagePageControllerTest.java
git commit -m "feat: 添加图片分页阅读与单页流式输出

散图与压缩包内页走同一个端点，阅读器不需要知道这本书是目录还是 CBZ。
ETag 手工比对而非 ShallowEtagHeaderFilter——后者要把整页读进内存算摘要，
正好抵消流式输出的意义。"
```

Expected: `EXIT=0`，`Tests run: 9, Failures: 0`

---

## Task 9: 阅读进度与继续阅读

**Files:**
- Create: `src/main/resources/db/migration/V9__image_progress.sql`
- Create: `src/main/java/com/mymedia/image/ImageProgress.java`
- Create: `src/main/java/com/mymedia/image/ImageProgressRepository.java`
- Create: `src/main/java/com/mymedia/image/ImageProgressService.java`
- Create: `src/main/java/com/mymedia/image/web/ImageProgressController.java`
- Test: `src/test/java/com/mymedia/image/ImageProgressServiceTest.java`

**Interfaces:**
- Consumes: `ImageNode`（Task 1）、`ImageCatalogService`（Task 3）、`UserAccount`（计划 01 Task 5）
- Produces:
  - `public class ImageProgress` — `Long getUserId()`、`Long getImageNodeId()`、`int getPageIndex()`、`Instant getUpdatedAt()`
  - `public class ImageProgressService`
    - `public void record(Long userId, Long nodeId, int pageIndex)`
    - `public Optional<ImageProgress> find(Long userId, Long nodeId)`
    - `public List<ImageProgress> continueReading(Long userId, int limit)`
  - `PUT /api/image/progress/{nodeId}`、`GET /api/image/continue-reading`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/image/ImageProgressServiceTest.java`：

```java
package com.mymedia.image;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImageProgressServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImageProgressService progressService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ImageCatalogService catalogService;

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    private MediaLibrary scannedLibrary() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        jobPoller.pollOnce();
        return library;
    }

    private UserAccount newUser() {
        return registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
    }

    @Test
    void recordsProgressForUser() throws IOException {
        writeImage("图集/001.jpg");
        writeImage("图集/002.jpg");
        writeImage("图集/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 1);

        assertThat(progressService.find(user.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(1);
    }

    @Test
    void progressIsPerUser() throws IOException {
        writeImage("图集2/001.jpg");
        writeImage("图集2/002.jpg");
        writeImage("图集2/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount alice = newUser();
        UserAccount bob = newUser();

        progressService.record(alice.getId(), nodeId, 1);
        progressService.record(bob.getId(), nodeId, 2);

        // 用户态数据独立成表，同一本书每个用户各读各的
        assertThat(progressService.find(alice.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(1);
        assertThat(progressService.find(bob.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(2);
    }

    @Test
    void repeatedRecordsOverwriteInsteadOfAccumulating() throws IOException {
        writeImage("图集3/001.jpg");
        writeImage("图集3/002.jpg");
        writeImage("图集3/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 0);
        progressService.record(user.getId(), nodeId, 1);
        progressService.record(user.getId(), nodeId, 2);

        assertThat(progressService.continueReading(user.getId(), 20)).hasSize(0);
        assertThat(progressService.find(user.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(2);
    }

    @Test
    void finishedBookDropsOutOfContinueReading() throws IOException {
        writeImage("读完的/001.jpg");
        writeImage("读完的/002.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 0);
        assertThat(progressService.continueReading(user.getId(), 20)).hasSize(1);

        progressService.record(user.getId(), nodeId, 1);   // 翻到最后一页
        assertThat(progressService.continueReading(user.getId(), 20)).isEmpty();
    }

    @Test
    void continueReadingIsOrderedByRecency() throws IOException {
        writeImage("甲/001.jpg");
        writeImage("甲/002.jpg");
        writeImage("甲/003.jpg");
        writeImage("乙/001.jpg");
        writeImage("乙/002.jpg");
        writeImage("乙/003.jpg");
        MediaLibrary library = scannedLibrary();
        var roots = catalogService.findRoots(library.getId());
        UserAccount user = newUser();

        progressService.record(user.getId(), roots.get(0).getId(), 1);
        progressService.record(user.getId(), roots.get(1).getId(), 1);

        assertThat(progressService.continueReading(user.getId(), 20))
                .extracting(ImageProgress::getImageNodeId)
                .containsExactly(roots.get(1).getId(), roots.get(0).getId());
    }

    @Test
    void rejectsNegativePageIndex() throws IOException {
        writeImage("图集4/001.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .with(httpBasic(user.getUsername(), "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointsRecordAndListProgress() throws IOException {
        writeImage("端点/001.jpg");
        writeImage("端点/002.jpg");
        writeImage("端点/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .with(httpBasic(user.getUsername(), "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":1}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/continue-reading")
                        .with(httpBasic(user.getUsername(), "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value(nodeId))
                .andExpect(jsonPath("$[0].pageIndex").value(1));
    }

    @Test
    void anonymousIsRejected() throws IOException {
        writeImage("匿名/001.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageProgressServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V9__image_progress.sql`：

```sql
-- 用户态数据独立成表，绝不塞进媒体表。这是多用户设计的核心：
-- 同一本漫画，每个用户读到哪一页互不干扰。
CREATE TABLE image_progress (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    image_node_id BIGINT      NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    page_index    INT         NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, image_node_id)
);

-- 「继续阅读」查询：按用户过滤、按时间倒序。
-- 这里没有 completed 列 —— 视频那边有，是因为时长要等 ffprobe 探测才知道，
-- 必须存快照；图片的总页数本来就在 image_node 上维护着，
-- 「读完没有」用一次连接就能算出来，存下来只会多一个会失效的冗余字段。
CREATE INDEX idx_image_progress_recent ON image_progress (user_id, updated_at DESC);
```

- [ ] **Step 4: 写实体与仓储**

`src/main/java/com/mymedia/image/ImageProgress.java`：

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
@Table(name = "image_progress")
@IdClass(ImageProgress.Key.class)
public class ImageProgress {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "image_node_id")
    private Long imageNodeId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ImageProgress() {
    }

    ImageProgress(Long userId, Long imageNodeId) {
        this.userId = userId;
        this.imageNodeId = imageNodeId;
    }

    public Long getUserId() { return userId; }
    public Long getImageNodeId() { return imageNodeId; }
    public int getPageIndex() { return pageIndex; }
    public Instant getUpdatedAt() { return updatedAt; }

    void update(int pageIndex) {
        this.pageIndex = pageIndex;
        this.updatedAt = Instant.now();
    }

    /** JPA 复合主键类。 */
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
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(userId, other.userId)
                    && Objects.equals(imageNodeId, other.imageNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, imageNodeId);
        }
    }
}
```

`src/main/java/com/mymedia/image/ImageProgressRepository.java`：

```java
package com.mymedia.image;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ImageProgressRepository extends JpaRepository<ImageProgress, ImageProgress.Key> {

    Optional<ImageProgress> findByUserIdAndImageNodeId(Long userId, Long imageNodeId);

    /**
     * 「继续阅读」：还没翻到最后一页的书，按最近阅读时间倒序。
     *
     * <p>用 {@code totalPageCount}（子树总页数）而不是直属页数：一个节点若还有
     * 子目录没读完，它本来就不算读完 —— 这正是想要的语义。
     */
    @Query("""
            SELECT p FROM ImageProgress p, ImageNode n
            WHERE p.imageNodeId = n.id
              AND p.userId = :userId
              AND p.pageIndex < n.totalPageCount - 1
            ORDER BY p.updatedAt DESC
            """)
    List<ImageProgress> findContinueReading(@Param("userId") Long userId, Pageable pageable);
}
```

- [ ] **Step 5: 写服务与端点**

`src/main/java/com/mymedia/image/ImageProgressService.java`：

```java
package com.mymedia.image;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ImageProgressService {

    private final ImageProgressRepository repository;
    private final ImageCatalogService catalogService;

    ImageProgressService(ImageProgressRepository repository, ImageCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void record(Long userId, Long nodeId, int pageIndex) {
        catalogService.getNode(nodeId);      // 不存在则抛 NotFoundException

        ImageProgress progress = repository.findByUserIdAndImageNodeId(userId, nodeId)
                .orElseGet(() -> new ImageProgress(userId, nodeId));
        progress.update(pageIndex);
        repository.save(progress);
    }

    @Transactional(readOnly = true)
    public Optional<ImageProgress> find(Long userId, Long nodeId) {
        return repository.findByUserIdAndImageNodeId(userId, nodeId);
    }

    @Transactional(readOnly = true)
    public List<ImageProgress> continueReading(Long userId, int limit) {
        return repository.findContinueReading(userId, PageRequest.of(0, limit));
    }
}
```

`src/main/java/com/mymedia/image/web/ImageProgressController.java`：

```java
package com.mymedia.image.web;

import com.mymedia.image.ImageProgress;
import com.mymedia.image.ImageProgressService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/image")
class ImageProgressController {

    private final ImageProgressService progressService;
    private final UserQueryService userQueryService;

    ImageProgressController(ImageProgressService progressService,
                            UserQueryService userQueryService) {
        this.progressService = progressService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/progress/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@AuthenticationPrincipal UserDetails principal,
                @PathVariable Long nodeId,
                @Valid @RequestBody ProgressRequest request) {
        progressService.record(currentUserId(principal), nodeId, request.pageIndex());
    }

    @GetMapping("/continue-reading")
    List<ProgressResponse> continueReading(@AuthenticationPrincipal UserDetails principal,
                                           @RequestParam(defaultValue = "20") int limit) {
        return progressService.continueReading(currentUserId(principal), limit).stream()
                .map(ProgressResponse::from)
                .toList();
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }

    record ProgressRequest(@Min(0) int pageIndex) {
    }

    record ProgressResponse(Long nodeId, int pageIndex) {

        static ProgressResponse from(ImageProgress progress) {
            return new ProgressResponse(progress.getImageNodeId(), progress.getPageIndex());
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ImageProgressServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V9__image_progress.sql src/main/java/com/mymedia/image src/test/java/com/mymedia/image/ImageProgressServiceTest.java
git commit -m "feat: 添加阅读进度与继续阅读

用户态数据独立成表，同一本书每个用户各读各的。
不存 completed：总页数本来就在节点上维护着，读完没有一次连接就能算，
存下来只会多一个会失效的冗余字段——视频那边存是因为时长要等探测。"
```

Expected: `EXIT=0`，`Tests run: 8, Failures: 0`

---

## Task 10: 全量验证与讲解文档

**Files:**
- Create: `docs/walkthrough/04-图片域.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: 前 9 个任务的全部产出
- Produces: 可交付的阶段成果

- [ ] **Step 1: 确认架构边界仍然成立**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`。

**关键检查**：架构测试若报出 `image -> video` 或 `video -> image` 的依赖，说明两个域被耦合了，必须修正而非放宽测试。两个域**只共享 `shared` 里的算法**（`NaturalSortKey`、`MaterializedPath`），不共享任何模型。

另外确认 `scan` 仍然不依赖 `image`：

```bash
cd /d/MyMedia && grep -rn "com.mymedia.image" src/main/java/com/mymedia/scan/ | head -5
```

Expected: 无输出。图片域是通过实现 SPI 接进扫描的，扫描代码里不该出现 `image` 这三个字。

- [ ] **Step 2: 运行全部测试**

```bash
cd /d/MyMedia && mvn -B -ntp verify > full.log 2>&1; echo "EXIT=$?"; grep -E "Tests run:|BUILD" full.log | tail -8
```

Expected: `EXIT=0`，`BUILD SUCCESS`，全部通过。**不通过不要继续。**

- [ ] **Step 3: 手工端到端验证**

```bash
cd /d/MyMedia && docker compose up -d

mkdir -p /tmp/mmi/画师A/2024 /tmp/mmi/漫画
for i in 1 2 10; do printf 'FAKE-JPEG-%s' "$i" > "/tmp/mmi/画师A/2024/$i.jpg"; done
mkdir -p /tmp/mmi/build/第1话 /tmp/mmi/build/第2话
printf 'P1' > /tmp/mmi/build/第1话/001.jpg
printf 'P2' > /tmp/mmi/build/第1话/002.jpg
printf 'P3' > /tmp/mmi/build/第2话/001.jpg
(cd /tmp/mmi/build && jar --create --file /tmp/mmi/漫画/vol01.cbz .)
rm -rf /tmp/mmi/build

mvn -B -ntp spring-boot:run > run.log 2>&1 &
sleep 30

LIB=$(curl -s -u admin:admin -X POST http://localhost:8080/api/libraries \
  -H 'Content-Type: application/json' \
  -d '{"name":"图片","domain":"IMAGE","rootPath":"/tmp/mmi"}' | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -u admin:admin -X POST "http://localhost:8080/api/libraries/$LIB/scan"
sleep 15

echo "=== 顶层节点 ==="
curl -s -u admin:admin http://localhost:8080/api/image/nodes
echo; echo "=== 树浏览 ==="
curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB"
```

Expected：顶层返回 `画师A` 与 `漫画` 两个节点；`画师A` 的 `browsable` 为 true、`readable` 为 false；`漫画` 下有一个 `vol01` 的 `ARCHIVE` 节点。

- [ ] **Step 4: 验证自然排序与压缩包随机读**

```bash
ALBUM=$(curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB" | python -c "
import sys,json
nodes = json.load(sys.stdin)['nodes']
print([n['id'] for n in nodes if n['name'] == '画师A'][0])")
YEAR=$(curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB&nodeId=$ALBUM" | python -c "
import sys,json; print(json.load(sys.stdin)['nodes'][0]['id'])")

echo "=== 散图页序（应为 1, 2, 10 而不是 1, 10, 2） ==="
curl -s -u admin:admin "http://localhost:8080/api/image/nodes/$YEAR/pages"

VOL=$(curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB" | python -c "
import sys,json
nodes = json.load(sys.stdin)['nodes']
print([n['id'] for n in nodes if n['name'] == '漫画'][0])")
CBZ=$(curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB&nodeId=$VOL" | python -c "
import sys,json; print(json.load(sys.stdin)['nodes'][0]['id'])")
PAGE=$(curl -s -u admin:admin "http://localhost:8080/api/image/nodes/$CBZ/pages" | python -c "
import sys,json; print(json.load(sys.stdin)[2]['id'])")

echo; echo "=== 压缩包第 3 页（不解压，直接读） ==="
curl -s -o /dev/null -D - -u admin:admin "http://localhost:8080/api/image/page/$PAGE" | head -8
ls /tmp/mmi/漫画        # 目录里不应出现任何解压产物
```

Expected：散图页序为 `1.jpg, 2.jpg, 10.jpg`；CBZ 第 3 页返回 `200` 并带 `ETag` 与 `Cache-Control: private`；`/tmp/mmi/漫画` 下只有 `vol01.cbz`。

- [ ] **Step 5: 验证目录改名不丢用户数据**

```bash
NODE=$(curl -s -u admin:admin "http://localhost:8080/api/image/browse?libraryId=$LIB&nodeId=$ALBUM" | python -c "
import sys,json; print(json.load(sys.stdin)['nodes'][0]['id'])")
curl -s -u admin:admin -X PUT "http://localhost:8080/api/image/progress/$NODE" \
  -H 'Content-Type: application/json' -d '{"pageIndex":1}'

mv "/tmp/mmi/画师A" "/tmp/mmi/画师A（已整理）"
curl -s -u admin:admin -X POST "http://localhost:8080/api/libraries/$LIB/scan"
sleep 15

echo "=== 改名后的继续阅读（进度必须还在） ==="
curl -s -u admin:admin http://localhost:8080/api/image/continue-reading
echo; echo "=== 顶层节点（不应出现重复或空壳） ==="
curl -s -u admin:admin http://localhost:8080/api/image/nodes
```

Expected：`continue-reading` 仍返回同一个 `nodeId`、`pageIndex` 为 1；顶层只有 `画师A（已整理）` 与 `漫画`，没有残留的 `画师A`。**这条是整个分层设计的最终验收。**

- [ ] **Step 6: 写讲解文档**

`docs/walkthrough/04-图片域.md`，必须覆盖以下九个问题：

1. **为什么图片域用自由树、视频域用语义模型？** 举出「画师/年份/合集」「作者/系列/单行本/卷」「来源/主题」三种真实深度不同的组织方式，说明为什么替用户定层级一定会错；再说明为什么视频域反过来不适合树。
2. **「书」与「文件夹」为什么不是互斥类型？** 举一个既有散图又有子目录的目录，说明 `readable` 与 `browsable` 两个布尔值同时为真时前端怎么渲染。
3. **两条路径分别管什么？** `materialized_path`（id 组成）管结构，`sort_path`（排序键组成）管顺序。为什么改名不会动结构路径，却必须重写顺序路径。
4. **子树移动为什么必须是一条 UPDATE？** 写出那条 SQL，解释 `substring(materialized_path FROM ?)` 里的旧值语义，以及深度为什么能用斜杠数算出来。再解释若改成逐层递归，代价是多少次往返。
5. **`LIKE '/1/17/%'` 为什么不会误匹配 `/1/170/`？** 收尾斜杠的作用，以及去掉它会出什么错。
6. **页码为什么不在发现文件时分配？** 用「中间插入一页」的例子说明逐个分配会让后面全错，再解释窗口函数那条 SQL 怎么一次搞定。
7. **CBZ 为什么绝不解压到磁盘？** ZIP 中央目录区的结构，`getInputStream(entry)` 做了什么，以及流关闭时不关压缩包会在 Windows 上出什么问题。
8. **压缩包里的中文文件名为什么会乱码？** 通用位标记第 11 位、UTF-8 与 GBK 的分流、为什么把回退编码设成 GBK 不会破坏 UTF-8 归档（附实测数据）。
9. **目录改名时用户数据是怎么保住的？** 从物理层的采样哈希配对讲到语义层的分组判定，说明「整组搬完才算目录移动」这个判据为什么必要，以及空壳节点是怎么冒出来又怎么被回收的。

- [ ] **Step 7: 更新 README 并提交**

在 README 功能清单追加：图片节点树浏览、CBZ 流式阅读、阅读模式覆盖、阅读进度、继续阅读、目录改名/移动无损跟随。

```bash
cd /d/MyMedia
rm -f full.log run.log t.log
rm -rf /tmp/mmi
git add -A
git commit -m "feat: 完成图片域阶段

任意深度节点树、CBZ 随机访问阅读、阅读模式覆盖、阅读进度，
以及目录改名与移动的子树跟随。附讲解文档。"
```

---

## Self-Review

**1. Spec 覆盖检查**

| spec 章节 | 覆盖任务 |
|---|---|
| §5.3 图片域端点 `GET /api/image/nodes` | Task 7 |
| §5.3 `GET /api/image/nodes/{id}/pages` | Task 7（列表）、Task 8（排序语义） |
| §5.3 `GET /api/image/page/{fileId}` | Task 8 |
| §5.3 `PUT /api/image/progress/{nodeId}` | Task 9 |
| §5.3 `GET /api/image/continue-reading` | Task 9 |
| §5.3 `GET /api/image/browse?nodeId=` | Task 7 |
| §5.1 域分区（复合外键 + CHECK） | Task 1 |
| §6.4 `image_node` 全部字段 | Task 1 |
| §6.4 「书/文件夹是同一节点的两种能力」 | Task 1（`isReadable`/`isBrowsable`）、Task 5（计数）、Task 7（DTO 两个布尔值） |
| §6.4 `reading_mode` 用户覆盖 | Task 1、3、7 |
| §6.4 计数字段增量维护 | Task 5 |
| §6.4 `image_file`（散图 vs 压缩包条目） | Task 1、3、4 |
| §6.4 「页不建树节点」 | Task 1（`image_file` 而非 `image_node`）、Task 4 |
| §6.5 `image_progress` | Task 9 |
| §7.1 步骤 6「增量维护 image_node 计数」（延自计划 02） | Task 5 |
| §7.1 坑：子树移动前缀替换（延自计划 02、03） | Task 6 |
| §7.1 坑：自然排序（延自计划 02） | Task 4（压缩包内页）、Task 5（散图页序）、Task 7（节点顺序） |
| §7.4 步骤 1 `ARCHIVE_INDEX` 建页索引 | Task 4 |
| §7.4 步骤 2 页序自然排序 | Task 4、5 |
| §7.4 步骤 3 `ZipFile.getInputStream` 随机访问 | Task 4、8 |
| §7.4 散图走同一接口、对前端透明 | Task 8 |
| 路线图 P6 / P7 | Task 1–6 / Task 7–9 |

**明确延后与偏离**（均已在正文标注理由，不是遗漏）：

- **§7.4 步骤 4「可选按 Accept 头转 WebP」——不实现。** spec 本身标为可选。ImageIO 没有 WebP 编码器，要么引第三方依赖、要么烘焙 `cwebp` 进镜像；收益是传输体积，代价是 CPU 与一条无法用"为什么需要它"讲清楚的依赖。取舍写进讲解文档。
- **§7.4 步骤 5「预读下一页」——服务端不做。** 预读是客户端行为：`GET /api/image/nodes/{id}/pages` 已经一次返回全部页 id，前端拿到就能预取下一页。服务端主动预读只会在用户跳页时白读一堆字节。
- **§7.4 「首次打开时建索引」→ 改为「扫描发现时建索引」**，理由见 Global Constraints。
- `image_node.cover_asset_id`、`title`、`metadata`、`scrape_*`：本计划**只建列不填**。封面由计划 05 的 `preview` 生成，标题与元数据由计划 05 的 `metadata` 刮削。`getDisplayName()` 已经写好回落逻辑（无 title 时用目录名），符合 spec §7.2 规则 1「无刮削亦完全可用」。
- `image_node_tag`、`image_favorite`：属于 P10，计划 06。
- 图片搜索：`idx_image_node_name_trgm` 索引本计划已建，查询端点属于 P10，计划 06。

**2. 占位符扫描**：已通过。两处"占位实现"（Task 3 Step 5 的 `ArchiveIndexJobHandler` 与 `ImageBrowseService`）**都明确标注了由哪个任务替换，并在该任务给出完整代码**，属于任务间的编译依赖处理，不是计划占位符。全部步骤含可直接执行的命令或完整代码。

**3. 类型一致性检查**

| 标识符 | 定义于 | 被引用于 | 一致 |
|---|---|---|---|
| `ImageSourceKind` / `ImageReadingMode` / `ImageNodeStatus` | Task 1 | Task 2、3、5、6、7 | ✓ |
| `ImageNode.directory/archive` 工厂方法 | Task 1 | Task 2 | ✓ |
| `ImageNode.finalizePaths(parentPath, parentSortPath)` | Task 1 | Task 2 | ✓ |
| `ImageNode.rename(newName, parentSortPath)` / `moveTo(parentId, parentPath, parentSortPath)` | Task 1 | Task 6 | ✓ |
| `ImageNode.setCounts(direct, child, total)` | Task 1 | Task 5（走 SQL，方法留给单元测试与计划 05） | ✓ |
| `ImageNode.isReadable/isBrowsable/getDisplayName` | Task 1 | Task 7 DTO、各测试 | ✓ |
| `ImageNode.assignCover(assetId)` | Task 1 | 计划 05 的 preview 回填 | ✓ |
| `ImageFile(scannedFileId, nodeId, fileName)`（散图） | Task 1 | Task 3 | ✓ |
| `ImageFile(scannedFileId, nodeId, entryName, pageIndex)`（压缩包） | Task 1 | Task 4 | ✓ |
| `ImageFile.reattachTo(nodeId)` | Task 1 | Task 6 | ✓ |
| `ImageFile.applyDimensions(...)` | Task 1 | 计划 05 的 preview 回填 | ✓ |
| `ImageNodeIndexer.directoryNodeFor/archiveNodeFor/findOrCreateChild` | Task 2 | Task 3、6 | ✓ |
| `ImageNodeIndexer.directoryPathNode/resolveDirectory` | Task 6（修改 Task 2 的类） | Task 6 | ✓ |
| `ImageNodeCreated(nodeId, libraryId, name)` | Task 2 | 计划 05 的 metadata / preview 订阅 | ✓ |
| `ImageCatalogService.getNode/findRoots/pagesOf/getFile/setReadingMode` | Task 3 | Task 7、8、9、各测试 | ✓ |
| `ImageFileRepository.findSubtreePages(libraryId, pathPrefix)` | Task 3 | Task 3（`pagesOf`） | ✓ |
| `ImageFileRepository.countByNodeId/findByScannedFileId/deleteByScannedFileId` | Task 1 | Task 4、6 | ✓ |
| `ArchivePage(entryName, sizeBytes)` | Task 4 | Task 4 | ✓ |
| `ImageArchiveReader.listPages/openEntry` | Task 4 | Task 4、8 | ✓ |
| `ArchiveIndexJobHandler.JOB_TYPE` | Task 3（占位）/ Task 4（实现） | Task 3 | ✓ |
| `ImageLibraryRecalculator.recalculate(libraryId)` | Task 5 | Task 5、6 的 `ImageScanFinalizer` | ✓ |
| `ImageScanFinalizer.onScanCompleted(LibraryScanCompleted)` | Task 5（建）/ Task 6（改） | Task 5 测试 | ✓ |
| `ImageTreeRelocator.applyPending(libraryId)` | Task 6 | Task 6 的 `ImageScanFinalizer` | ✓ |
| `ImageBrowseService.childNodes/getNode/breadcrumb` | Task 3（占位）/ Task 7（实现） | Task 3、4、7 测试、Task 7 控制器 | ✓ |
| `ImageNodeDto.NodeSummary/PageSummary/BrowseResponse/ReadingModeRequest` | Task 7 | Task 7 控制器 | ✓ |
| `ImagePageService.locate/open` + `PageTarget(path, archiveEntryName, sizeBytes, etag, lastModified, contentType)` | Task 8 | Task 8 控制器 | ✓ |
| `ImageProgressService.record/find/continueReading` | Task 9 | Task 9 控制器、测试 | ✓ |
| `NaturalSortKey.of(String)`（计划 03） | 计划 03 Task 1 | Task 1、4 | ✓ |
| `MaterializedPath.rootPath/childOf/ancestorIds/depthOf`（计划 03） | 计划 03 Task 2 | Task 1、2、5、6、7 | ✓ |
| `LibraryContentBuilder`（计划 02） | 计划 02 Task 7 | Task 3 实现 | ✓ |
| `JobPoller.pollOnce()`（计划 02） | 计划 02 Task 7 | Task 3–9 的测试 | ✓ |

**编写中发现并已处理的三处**

1. **`commons-compress` 从未真的进过 `pom.xml`。** 计划 02 的约束表里写了「计划 04 用，本计划先引入」，但那个计划的所有步骤都没碰 `pom.xml`。已在本计划 Task 4 Step 3 补上，并附实测过的版本来源。
2. **Task 3 的测试依赖 Task 4 与 Task 7 的类型。** 已在 Task 3 Step 5 给出两个最小占位并明确标注替换任务，使每个任务都能独立通过自己的测试。
3. **`ImageNodeIndexer` 需要「只查不建」的路径解析。** Task 2 只需要 find-or-create，但 Task 6 必须能区分「这个目录还在树里」和「它已经跟着祖先搬走了」——用 find-or-create 会把后者错认成前者并造出空壳。已在 Task 6 Step 3 以「修改 Task 2 的类」的形式补上，给出完整方法体。

**跨计划发现（已回填计划 03）**

计划 03 的视频域有一个同源问题本计划已经解决、而它没有：扫描先发布「发现新文件」、随后改名配对才把新记录删掉，这中间 `VideoContentBuilder` 已经按新文件名建好了一个 `VideoItem`。配对成功后 `video_file` 随 `scanned_file` 级联删除，但**那个 `VideoItem` 会作为无文件的孤儿留下来**——重命名一部电影会让 `GET /api/video/items` 多出一条空条目。计划 03 的 `renamingFileKeepsTheSameVideoFileRow` 只断言了原条目的文件没变，照不到这个。

图片域的做法是扫描收尾时回收「零页零子节点」的目录节点（Task 5）。视频域的对应修法已回填进**计划 03 Task 5 Step 7**：新增 `VideoScanFinalizer`，在 `LibraryScanCompleted` 时删除没有任何 `video_file` 的 `video_item`，并补了 `renamingLeavesNoOrphanItem` 测试（该任务的用例数由 6 变 7，原 Step 7 顺延为 Step 8）。

两边都安全的同一个理由：文件**消失**时物理层只标记 `MISSING`、语义层一行不删，所以外接盘没挂载不会触发回收；只有真正一个文件都不剩的条目/节点才会被清掉。
