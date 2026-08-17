# MyMedia 实施计划 03：视频域

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现视频域的完整链路：语义模型（作品 / 季 / 文件 / 合集）、文件名解析、目录树浏览视图、HTTP Range 流式播放、播放进度与继续观看。

**Architecture:** `video` 模块实现 `scan` 模块定义的 `LibraryContentBuilder` SPI，把物理文件构建成语义结构。`video` 与 `image` 互不依赖。树路径算法与自然排序抽到 `shared` 模块，供两个域复用——**复用算法，不复用模型**。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · PostgreSQL 17 · NIO.2 `FileChannel.transferTo`

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`（覆盖 §5.3 接口分区、§6.3 视频域数据模型、§7.3 视频流式传输、路线图 P4–P5）

**前置计划:** 01 基础设施、02 扫描框架 必须全部完成且 `mvn verify` 通过。

---

## Global Constraints

**继承计划 01 与 02 的全部 Global Constraints。执行前必须先读一遍计划 01 的该章节。**

本计划新增：

### 表命名（与 spec §6.3 同步修订）

原 spec 中的 `media_item` / `media_group` 已统一改名为 **`video_item` / `video_group`**。理由：它们是视频域专属的（图片域走 `image_node`），叫 "media" 会误导。改名后与 `image_node` / `image_file` 对称。`collection` 保持原名（已有 `domain` 列约束）。

### 结构由扫描判定，不由类型推导

`video_item.structure`（`FLAT` / `GROUPED`）是**独立字段**，不从 `item_type` 推导。一部"电影"若实际含多个部分，同样可以是 `GROUPED`。这是 spec §6.3 的明确要求，实现时不得偷懒把两者绑死。

### Range 语义不得简化

`GET /api/video/stream/{fileId}` 必须完整实现 `206` / `Content-Range` / `Accept-Ranges` / `If-Range` / `416`。这是本项目最核心的技术展示点，任何"先返回整个文件、以后再补"的做法都不接受。

**多重 Range**（`bytes=0-99,200-299`）按 spec §7.3 的决策**返回并集**（覆盖最小起点到最大终点的单一区间），不实现 `multipart/byteranges`。浏览器的 `<video>` 元素不发送多重 Range。

---

## File Structure

```
src/main/java/com/mymedia/shared/
├── NaturalSortKey.java              自然排序键（video 与 image 共用）
└── MaterializedPath.java            物化路径运算（video 与 image 共用）

src/main/java/com/mymedia/video/
├── package-info.java
├── VideoItem.java                   实体 → 表 video_item
├── VideoItemType.java               枚举 MOVIE/SERIES/SINGLE_VIDEO/VIDEO_SERIES
├── VideoStructure.java              枚举 FLAT/GROUPED
├── VideoGroup.java                  实体 → 表 video_group（季/分册）
├── VideoFile.java                   实体 → 表 video_file
├── VideoFileRole.java               枚举 PRIMARY/VERSION/EXTRA/SUBTITLE/TRAILER
├── VideoFolder.java                 实体 → 表 video_folder（目录树浏览视图）
├── *Repository.java                 package-private
├── VideoFilenameParser.java         package-private：文件名解析
├── ParsedVideoName.java             package-private：解析结果
├── VideoContentBuilder.java         package-private：实现 scan 的 SPI
├── VideoFolderIndexer.java          package-private：维护目录树
├── VideoCatalogService.java         public API：条目查询
├── VideoBrowseService.java          public API：目录树浏览
├── VideoStreamService.java          public API：文件定位与鉴权
├── VideoProgressService.java        public API：播放进度
├── range/
│   ├── RangeParser.java             package-private：Range 头解析
│   └── RangeResolution.java         package-private：解析结果 sealed interface
└── web/
    ├── VideoCatalogController.java
    ├── VideoBrowseController.java
    ├── VideoStreamController.java
    └── VideoProgressController.java

src/main/resources/db/migration/
├── V6__video_domain.sql
└── V7__video_progress.sql

src/test/java/com/mymedia/
├── shared/NaturalSortKeyTest.java
├── shared/MaterializedPathTest.java
└── video/
    ├── VideoFilenameParserTest.java
    ├── VideoContentBuilderTest.java
    ├── VideoFolderIndexerTest.java
    ├── range/RangeParserTest.java
    ├── VideoStreamControllerTest.java
    └── VideoProgressServiceTest.java
```

---

## Task 1: 自然排序键

字典序会把 `第1卷, 第2卷, 第10卷` 排成 `1, 10, 2`。这是媒体库必踩的坑，且 `video` 与 `image` 两个域都需要。

**Files:**
- Create: `src/main/java/com/mymedia/shared/NaturalSortKey.java`
- Test: `src/test/java/com/mymedia/shared/NaturalSortKeyTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `public final class NaturalSortKey` — `public static String of(String name)`，返回可直接用字典序比较的键

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/shared/NaturalSortKeyTest.java`：

```java
package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalSortKeyTest {

    private List<String> sortNaturally(List<String> input) {
        return input.stream()
                .sorted(Comparator.comparing(NaturalSortKey::of))
                .toList();
    }

    @Test
    void sortsNumbersNumericallyNotLexically() {
        List<String> sorted = sortNaturally(List.of("第10卷", "第2卷", "第1卷"));

        assertThat(sorted).containsExactly("第1卷", "第2卷", "第10卷");
    }

    @Test
    void handlesEpisodeNumbering() {
        List<String> sorted = sortNaturally(List.of("E11.mkv", "E2.mkv", "E1.mkv", "E20.mkv"));

        assertThat(sorted).containsExactly("E1.mkv", "E2.mkv", "E11.mkv", "E20.mkv");
    }

    @Test
    void handlesMultipleNumberGroups() {
        List<String> sorted = sortNaturally(List.of("S1E10", "S1E2", "S10E1", "S2E1"));

        assertThat(sorted).containsExactly("S1E2", "S1E10", "S2E1", "S10E1");
    }

    @Test
    void treatsZeroPaddedNumbersAsEqualValue() {
        // 001 与 1 应排在一起，不因补零而分开
        assertThat(NaturalSortKey.of("ep001")).isEqualTo(NaturalSortKey.of("ep1"));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(NaturalSortKey.of("Movie")).isEqualTo(NaturalSortKey.of("movie"));
    }

    @Test
    void handlesVeryLargeNumbersWithoutOverflow() {
        List<String> sorted = sortNaturally(List.of(
                "x99999999999999999999", "x2", "x100"));

        assertThat(sorted).containsExactly("x2", "x100", "x99999999999999999999");
    }

    @Test
    void handlesPureText() {
        List<String> sorted = sortNaturally(List.of("banana", "apple", "cherry"));

        assertThat(sorted).containsExactly("apple", "banana", "cherry");
    }

    @Test
    void handlesEmptyAndNumberOnly() {
        assertThat(NaturalSortKey.of("")).isNotNull();
        assertThat(sortNaturally(List.of("10", "2", "1"))).containsExactly("1", "2", "10");
    }

    @Test
    void handlesChineseText() {
        List<String> sorted = sortNaturally(List.of("进击的巨人 第10话", "进击的巨人 第2话"));

        assertThat(sorted).containsExactly("进击的巨人 第2话", "进击的巨人 第10话");
    }
}
```

> **`handlesVeryLargeNumbersWithoutOverflow`** 是关键：把数字段解析成 `long` 会在 20 位数上溢出。正确做法是**按位数长度前缀 + 原数字**，纯字符串操作，无溢出可能。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=NaturalSortKeyTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现自然排序键**

`src/main/java/com/mymedia/shared/NaturalSortKey.java`：

```java
package com.mymedia.shared;

import java.util.Locale;

/**
 * 把名称转换成可用<b>字典序</b>直接比较的键，使其中的数字按数值大小排序。
 *
 * <p>不加处理时字典序会把 {@code 第1卷, 第2卷, 第10卷} 排成 {@code 1, 10, 2}。
 * 这是媒体库必踩的坑，视频域的集号与图片域的卷号都依赖它。
 *
 * <p>算法：把每一段连续数字替换成 {@code 长度位数 + 长度 + 数值}，
 * 例如 {@code 10} → {@code 2:10}、{@code 2} → {@code 1:2}。
 * 因为 {@code "1:"} 字典序小于 {@code "2:"}，位数少的数字自然排在前面；
 * 位数相同则退化为按数值逐位比较。
 *
 * <p>全程纯字符串操作，<b>不解析成 long</b>——20 位以上的数字会溢出。
 *
 * <p>键在写入时预计算并存进 {@code sort_key} 列，查询时直接 ORDER BY，
 * 不在每次查询时重新计算。
 */
public final class NaturalSortKey {

    private NaturalSortKey() {
    }

    public static String of(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(lower.length() + 8);

        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (!isAsciiDigit(c)) {
                key.append(c);
                i++;
                continue;
            }

            int start = i;
            while (i < lower.length() && isAsciiDigit(lower.charAt(i))) {
                i++;
            }
            String digits = lower.substring(start, i);

            // 去掉前导零，使 001 与 1 得到相同的键
            int firstSignificant = 0;
            while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
                firstSignificant++;
            }
            String normalized = digits.substring(firstSignificant);

            // 位数前缀本身也可能多位（如 100 位的数字），再套一层长度标记
            String lengthMarker = String.valueOf(normalized.length());
            key.append(lengthMarker.length()).append(':').append(lengthMarker).append(':')
                    .append(normalized);
        }
        return key.toString();
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
```

- [ ] **Step 4: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=NaturalSortKeyTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/shared/NaturalSortKey.java src/test/java/com/mymedia/shared/NaturalSortKeyTest.java
git commit -m "feat: 添加自然排序键

纯字符串实现，不解析成 long——20 位以上数字会溢出。
键在写入时预计算存进 sort_key 列，查询直接 ORDER BY。"
```

Expected: `EXIT=0`，`Tests run: 9, Failures: 0`

---

## Task 2: 物化路径运算

`video_folder` 与 `image_node` 两棵树都要用。

**Files:**
- Create: `src/main/java/com/mymedia/shared/MaterializedPath.java`
- Test: `src/test/java/com/mymedia/shared/MaterializedPathTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `public final class MaterializedPath`
  - `public static String rootPath()` → `"/"`
  - `public static String childOf(String parentPath, Long parentId)`
  - `public static List<Long> ancestorIds(String path)`
  - `public static int depthOf(String path)`
  - `public static String subtreePrefix(String path)` — 用于 `LIKE` 前缀查询
  - `public static String rewrite(String path, String oldPrefix, String newPrefix)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/shared/MaterializedPathTest.java`：

```java
package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterializedPathTest {

    @Test
    void rootPathIsSingleSlash() {
        assertThat(MaterializedPath.rootPath()).isEqualTo("/");
    }

    @Test
    void childAppendsParentIdAndTrailingSlash() {
        assertThat(MaterializedPath.childOf("/", 1L)).isEqualTo("/1/");
        assertThat(MaterializedPath.childOf("/1/", 17L)).isEqualTo("/1/17/");
        assertThat(MaterializedPath.childOf("/1/17/", 93L)).isEqualTo("/1/17/93/");
    }

    @Test
    void ancestorIdsAreParsedInOrder() {
        assertThat(MaterializedPath.ancestorIds("/1/17/93/")).containsExactly(1L, 17L, 93L);
        assertThat(MaterializedPath.ancestorIds("/")).isEmpty();
    }

    @Test
    void depthCountsSegments() {
        assertThat(MaterializedPath.depthOf("/")).isZero();
        assertThat(MaterializedPath.depthOf("/1/")).isEqualTo(1);
        assertThat(MaterializedPath.depthOf("/1/17/93/")).isEqualTo(3);
    }

    @Test
    void subtreePrefixMatchesDescendantsOnly() {
        String prefix = MaterializedPath.subtreePrefix("/1/17/");

        assertThat("/1/17/93/").startsWith(prefix);
        assertThat("/1/17/").startsWith(prefix);
        // 关键：/1/170/ 不是 /1/17/ 的子树，前缀必须以斜杠收尾才不会误匹配
        assertThat("/1/170/".startsWith(prefix)).isFalse();
    }

    @Test
    void rewriteReplacesPrefixForSubtreeMove() {
        // 把 /1/17/ 整棵子树移到 /5/ 下面
        String moved = MaterializedPath.rewrite("/1/17/93/", "/1/17/", "/5/17/");

        assertThat(moved).isEqualTo("/5/17/93/");
    }

    @Test
    void rewriteLeavesUnrelatedPathsUntouched() {
        assertThat(MaterializedPath.rewrite("/2/8/", "/1/17/", "/5/17/")).isEqualTo("/2/8/");
    }

    @Test
    void rejectsMalformedPath() {
        assertThatThrownBy(() -> MaterializedPath.ancestorIds("1/17"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializedPath.childOf("/1", 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesDeepPaths() {
        String path = MaterializedPath.rootPath();
        for (long i = 1; i <= 32; i++) {
            path = MaterializedPath.childOf(path, i);
        }

        assertThat(MaterializedPath.depthOf(path)).isEqualTo(32);
        assertThat(MaterializedPath.ancestorIds(path)).hasSize(32).endsWith(32L);
    }
}
```

> **`subtreePrefix` 必须以斜杠收尾**：否则 `/1/17` 会误匹配 `/1/170/`，把不相干的子树一并卷进查询和移动操作。这是物化路径最经典的 bug。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=MaterializedPathTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现物化路径**

`src/main/java/com/mymedia/shared/MaterializedPath.java`：

```java
package com.mymedia.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * 树节点的物化路径运算，形如 {@code /1/17/93/}。
 *
 * <p>存物化路径的收益：子树查询是一次前缀索引扫描（{@code LIKE '/1/17/%'}），
 * 面包屑导航直接解析路径即可得到全部祖先 id，都不需要递归查询。
 *
 * <p>代价：移动子树时必须重写整棵子树的路径。{@link #rewrite} 就是为此存在——
 * 数据库端用一条前缀替换 UPDATE 完成，不可逐层递归。
 *
 * <p>{@code video} 与 {@code image} 两个域各有自己的树表，但共用本工具：
 * <b>复用算法，不复用模型</b>。
 */
public final class MaterializedPath {

    private static final String SEPARATOR = "/";

    private MaterializedPath() {
    }

    public static String rootPath() {
        return SEPARATOR;
    }

    public static String childOf(String parentPath, Long parentId) {
        requireWellFormed(parentPath);
        if (parentId == null) {
            throw new IllegalArgumentException("父节点 id 不能为 null");
        }
        return parentPath + parentId + SEPARATOR;
    }

    public static List<Long> ancestorIds(String path) {
        requireWellFormed(path);
        List<Long> ids = new ArrayList<>();
        for (String segment : path.split(SEPARATOR)) {
            if (!segment.isEmpty()) {
                ids.add(Long.valueOf(segment));
            }
        }
        return ids;
    }

    public static int depthOf(String path) {
        return ancestorIds(path).size();
    }

    /**
     * 子树查询的 LIKE 前缀。
     *
     * <p><b>必须以斜杠收尾</b>：否则 {@code /1/17} 会误匹配 {@code /1/170/}，
     * 把不相干的子树卷进查询与移动操作。这是物化路径最经典的 bug。
     */
    public static String subtreePrefix(String path) {
        requireWellFormed(path);
        return path;
    }

    /**
     * 子树移动时重写路径：把 {@code oldPrefix} 换成 {@code newPrefix}。
     * 不以 {@code oldPrefix} 开头的路径原样返回。
     */
    public static String rewrite(String path, String oldPrefix, String newPrefix) {
        requireWellFormed(path);
        requireWellFormed(oldPrefix);
        requireWellFormed(newPrefix);
        if (!path.startsWith(oldPrefix)) {
            return path;
        }
        return newPrefix + path.substring(oldPrefix.length());
    }

    private static void requireWellFormed(String path) {
        if (path == null || !path.startsWith(SEPARATOR) || !path.endsWith(SEPARATOR)) {
            throw new IllegalArgumentException("物化路径必须以斜杠开头并以斜杠结尾: " + path);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=MaterializedPathTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/shared/MaterializedPath.java src/test/java/com/mymedia/shared/MaterializedPathTest.java
git commit -m "feat: 添加物化路径运算

子树前缀必须以斜杠收尾，否则 /1/17 会误匹配 /1/170。
video 与 image 共用本工具：复用算法，不复用模型。"
```

Expected: `EXIT=0`，`Tests run: 9, Failures: 0`

---

## Task 3: 视频域表与实体

**Files:**
- Create: `src/main/resources/db/migration/V6__video_domain.sql`
- Create: `src/main/java/com/mymedia/video/package-info.java`
- Create: `src/main/java/com/mymedia/video/VideoItemType.java`
- Create: `src/main/java/com/mymedia/video/VideoStructure.java`
- Create: `src/main/java/com/mymedia/video/VideoFileRole.java`
- Create: `src/main/java/com/mymedia/video/VideoItem.java`
- Create: `src/main/java/com/mymedia/video/VideoGroup.java`
- Create: `src/main/java/com/mymedia/video/VideoFile.java`
- Create: `src/main/java/com/mymedia/video/VideoFolder.java`
- Create: `src/main/java/com/mymedia/video/VideoItemRepository.java`
- Create: `src/main/java/com/mymedia/video/VideoGroupRepository.java`
- Create: `src/main/java/com/mymedia/video/VideoFileRepository.java`
- Create: `src/main/java/com/mymedia/video/VideoFolderRepository.java`
- Test: `src/test/java/com/mymedia/video/VideoDomainConstraintTest.java`

**Interfaces:**
- Consumes: `MediaLibrary`、`LibraryDomain`（计划 01）、`ScannedFile`（计划 02）
- Produces:
  - `public enum VideoItemType { MOVIE, SERIES, SINGLE_VIDEO, VIDEO_SERIES }`
  - `public enum VideoStructure { FLAT, GROUPED }`
  - `public enum VideoFileRole { PRIMARY, VERSION, EXTRA, SUBTITLE, TRAILER }`
  - `public class VideoItem` — getter：`Long getId()`、`Long getLibraryId()`、`Long getFolderId()`、`VideoItemType getItemType()`、`VideoStructure getStructure()`、`String getTitle()`、`String getSortTitle()`
  - `public class VideoGroup` — `Long getId()`、`Long getItemId()`、`int getGroupIndex()`、`String getName()`
  - `public class VideoFile` — `Long getId()`、`Long getScannedFileId()`、`Long getItemId()`、`Long getGroupId()`、`VideoFileRole getRole()`、`Integer getEpisodeIndex()`、`Integer getDurationSeconds()`
  - `public class VideoFolder` — `Long getId()`、`Long getParentId()`、`String getMaterializedPath()`、`String getName()`、`int getDepth()`

- [ ] **Step 1: 写会失败的约束测试**

`src/test/java/com/mymedia/video/VideoDomainConstraintTest.java`：

```java
package com.mymedia.video;

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

class VideoDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary library(LibraryDomain domain) {
        return libraryService.create("库" + UUID.randomUUID(), domain, "/media/" + UUID.randomUUID());
    }

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'VIDEO', 'MOVIE', 'FLAT', ?, ?)
                RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @Test
    void videoItemCanBeCreatedInVideoLibrary() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        Long id = insertItem(videoLib.getId(), "黑客帝国");

        assertThat(id).isNotNull();
    }

    @Test
    void videoItemCannotLiveInImageLibrary() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        // 这是域分区的核心保证：复合外键让视频条目无法落进图片库
        assertThatThrownBy(() -> insertItem(imageLib.getId(), "不该存在"))
                .hasMessageContaining("fk_video_item_library_domain");
    }

    @Test
    void domainColumnCannotBeSetToImage() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'IMAGE', 'MOVIE', 'FLAT', 'x', 'x')
                """, videoLib.getId()))
                .hasMessageContaining("ck_video_item_is_video");
    }

    @Test
    void rejectsUnknownItemType() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'VIDEO', 'PODCAST', 'FLAT', 'x', 'x')
                """, videoLib.getId()))
                .hasMessageContaining("ck_video_item_type");
    }

    @Test
    void videoFileRequiresUniqueScannedFile() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);
        Long itemId = insertItem(videoLib.getId(), "片子");
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, 'a.mkv', 100, now(), 'mkv', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, videoLib.getId());

        jdbc.update("""
                INSERT INTO video_file (scanned_file_id, item_id, role)
                VALUES (?, ?, 'PRIMARY')
                """, scannedId, itemId);

        // 一个物理文件只能对应一个语义条目
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_file (scanned_file_id, item_id, role)
                VALUES (?, ?, 'VERSION')
                """, scannedId, itemId))
                .hasMessageContaining("uq_video_file_scanned");
    }

    @Test
    void deletingScannedFileCascadesToVideoFile() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);
        Long itemId = insertItem(videoLib.getId(), "片子");
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, 'b.mkv', 100, now(), 'mkv', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, videoLib.getId());
        jdbc.update("INSERT INTO video_file (scanned_file_id, item_id, role) VALUES (?, ?, 'PRIMARY')",
                scannedId, itemId);

        jdbc.update("DELETE FROM scanned_file WHERE id = ?", scannedId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM video_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(remaining).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -8
```

Expected: 失败，`video_item` 表不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V6__video_domain.sql`：

```sql
-- ============================================================
-- 视频域语义层。图片域走 image_node / image_file，两者刻意不对称：
-- 视频语义强（一部电影、一季是刮削与播放的天然单位），
-- 图片组织高度个人化，需要任意深度的自由树。详见 spec 6.4。
-- ============================================================

-- 目录树浏览视图（派生索引，非主模型）。
-- 视频域的主浏览方式是语义化的；本表只承载导航，不承载元数据与进度。
CREATE TABLE video_folder (
    id                BIGSERIAL PRIMARY KEY,
    library_id        BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    parent_id         BIGINT      REFERENCES video_folder (id) ON DELETE CASCADE,
    materialized_path TEXT        NOT NULL,
    depth             INT         NOT NULL,
    name              TEXT        NOT NULL,
    sort_key          TEXT        NOT NULL,
    direct_item_count INT         NOT NULL DEFAULT 0,
    total_item_count  INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE video_folder
    ADD CONSTRAINT uq_video_folder_path UNIQUE (library_id, materialized_path);

-- text_pattern_ops 让 LIKE '前缀%' 能走索引（默认的排序规则下不行）
CREATE INDEX idx_video_folder_subtree
    ON video_folder (library_id, materialized_path text_pattern_ops);
CREATE INDEX idx_video_folder_parent ON video_folder (parent_id, sort_key);

-- 一个"作品"：一部电影 / 一部番 / 一个系列
CREATE TABLE video_item (
    id             BIGSERIAL PRIMARY KEY,
    library_id     BIGINT       NOT NULL,
    domain         VARCHAR(8)   NOT NULL DEFAULT 'VIDEO',
    folder_id      BIGINT       REFERENCES video_folder (id) ON DELETE SET NULL,
    item_type      VARCHAR(16)  NOT NULL,
    structure      VARCHAR(8)   NOT NULL DEFAULT 'FLAT',
    title          TEXT         NOT NULL,
    original_title TEXT,
    sort_title     TEXT         NOT NULL,
    summary        TEXT,
    release_date   DATE,
    rating         NUMERIC(3,1),
    cover_asset_id BIGINT,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    raw_metadata   JSONB,
    field_sources  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    locked_fields  TEXT[]       NOT NULL DEFAULT '{}',
    scrape_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    scrape_source  VARCHAR(32),
    scrape_source_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_video_item_type CHECK (
        item_type IN ('MOVIE', 'SERIES', 'SINGLE_VIDEO', 'VIDEO_SERIES')),
    CONSTRAINT ck_video_item_structure CHECK (structure IN ('FLAT', 'GROUPED')),
    CONSTRAINT ck_video_item_scrape_status CHECK (scrape_status IN (
        'NOT_APPLICABLE', 'PENDING', 'MATCHED', 'NO_MATCH', 'NEEDS_REVIEW', 'ERROR')),
    -- 域分区的数据库级强制，见 ADR-001
    CONSTRAINT ck_video_item_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_video_item_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

CREATE INDEX idx_video_item_library ON video_item (library_id, sort_title);
CREATE INDEX idx_video_item_folder ON video_item (folder_id, sort_title);
-- 中文搜索主路径，见 spec 7.7
CREATE INDEX idx_video_item_title_trgm ON video_item USING gin (title gin_trgm_ops);

-- 可选分组：季 / 分册。仅 structure = 'GROUPED' 时存在。
CREATE TABLE video_group (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    group_index    INT         NOT NULL,
    name           TEXT        NOT NULL,
    sort_key       TEXT        NOT NULL,
    summary        TEXT,
    cover_asset_id BIGINT,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_video_group_index UNIQUE (item_id, group_index)
);

-- 语义层。item_id 必填、group_id 可空 —— 外键单一，
-- 不需要"隐式分组"这类绕弯设计。
CREATE TABLE video_file (
    id               BIGSERIAL PRIMARY KEY,
    scanned_file_id  BIGINT      NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    item_id          BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    group_id         BIGINT      REFERENCES video_group (id) ON DELETE SET NULL,
    role             VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    episode_index    INT,
    sort_key         TEXT        NOT NULL DEFAULT '',
    duration_seconds INT,
    width            INT,
    height           INT,
    video_codec      VARCHAR(32),
    audio_codec      VARCHAR(32),
    bitrate          BIGINT,
    container        VARCHAR(16),
    probe_raw        JSONB,
    CONSTRAINT ck_video_file_role CHECK (
        role IN ('PRIMARY', 'VERSION', 'EXTRA', 'SUBTITLE', 'TRAILER'))
);

-- 一个物理文件只能对应一个语义条目
ALTER TABLE video_file ADD CONSTRAINT uq_video_file_scanned UNIQUE (scanned_file_id);
CREATE INDEX idx_video_file_item ON video_file (item_id, sort_key);
CREATE INDEX idx_video_file_group ON video_file (group_id, episode_index);

-- 跨条目聚合：一部电影可同时属于「指环王三部曲」与「托尔金改编作品」
CREATE TABLE collection (
    id             BIGSERIAL PRIMARY KEY,
    library_id     BIGINT      NOT NULL,
    domain         VARCHAR(8)  NOT NULL DEFAULT 'VIDEO',
    name           TEXT        NOT NULL,
    sort_key       TEXT        NOT NULL,
    summary        TEXT,
    cover_asset_id BIGINT,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_collection_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_collection_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

CREATE TABLE collection_item (
    collection_id BIGINT NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    video_item_id BIGINT NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    sort_order    INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (collection_id, video_item_id)
);
```

- [ ] **Step 4: 写枚举**

`src/main/java/com/mymedia/video/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "Video")
package com.mymedia.video;
```

`src/main/java/com/mymedia/video/VideoItemType.java`：

```java
package com.mymedia.video;

public enum VideoItemType { MOVIE, SERIES, SINGLE_VIDEO, VIDEO_SERIES }
```

`src/main/java/com/mymedia/video/VideoStructure.java`：

```java
package com.mymedia.video;

/**
 * 条目的内部结构。
 *
 * <p><b>这是独立字段，不由 {@link VideoItemType} 推导。</b>
 * 一部「电影」若实际含多个部分，同样可以是 {@code GROUPED}。
 * 扫描时按实际目录结构判定，用户可手动更改。
 */
public enum VideoStructure {
    /** 条目直接挂文件，无分组层。 */
    FLAT,
    /** 条目 → 分组（季/分册） → 文件。 */
    GROUPED
}
```

`src/main/java/com/mymedia/video/VideoFileRole.java`：

```java
package com.mymedia.video;

/**
 * 文件在条目中扮演的角色。
 *
 * <p>使得一个 {@code FLAT} 条目也能拥有多个文件：
 * 一部电影可以有 1080p 与 4K 两个版本、外加花絮与预告片。
 */
public enum VideoFileRole { PRIMARY, VERSION, EXTRA, SUBTITLE, TRAILER }
```

- [ ] **Step 5: 写实体**

`src/main/java/com/mymedia/video/VideoItem.java`：

```java
package com.mymedia.video;

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

@Entity
@Table(name = "video_item")
public class VideoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    /** 恒为 "VIDEO"。复合外键把它钉死在所属库的 domain 上，见 ADR-001。 */
    @Column(nullable = false, length = 8, updatable = false)
    private String domain = "VIDEO";

    @Column(name = "folder_id")
    private Long folderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 16)
    private VideoItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private VideoStructure structure = VideoStructure.FLAT;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "sort_title", nullable = false)
    private String sortTitle;

    @Column
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VideoItem() {
        // JPA 要求的无参构造器
    }

    VideoItem(Long libraryId, VideoItemType itemType, VideoStructure structure, String title) {
        this.libraryId = libraryId;
        this.itemType = itemType;
        this.structure = structure;
        this.title = title;
        this.sortTitle = NaturalSortKey.of(title);
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getFolderId() { return folderId; }
    public VideoItemType getItemType() { return itemType; }
    public VideoStructure getStructure() { return structure; }
    public String getTitle() { return title; }
    public String getOriginalTitle() { return originalTitle; }
    public String getSortTitle() { return sortTitle; }
    public String getSummary() { return summary; }

    void assignFolder(Long folderId) {
        this.folderId = folderId;
    }

    /** 首次发现分组文件时把 FLAT 提升为 GROUPED。 */
    void promoteToGrouped() {
        this.structure = VideoStructure.GROUPED;
    }

    void rename(String newTitle) {
        this.title = newTitle;
        this.sortTitle = NaturalSortKey.of(newTitle);
    }
}
```

`src/main/java/com/mymedia/video/VideoGroup.java`：

```java
package com.mymedia.video;

import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "video_group")
public class VideoGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    @Column(name = "group_index", nullable = false)
    private int groupIndex;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column
    private String summary;

    protected VideoGroup() {
    }

    VideoGroup(Long itemId, int groupIndex, String name) {
        this.itemId = itemId;
        this.groupIndex = groupIndex;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public int getGroupIndex() { return groupIndex; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
}
```

`src/main/java/com/mymedia/video/VideoFile.java`：

```java
package com.mymedia.video;

import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "video_file")
public class VideoFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 指向物理层。文件改名或移动时只有 {@code scanned_file.relative_path} 变化，
     * 本表与用户播放进度完全不受影响 —— 这是 spec 6.1 分层设计的收益。
     */
    @Column(name = "scanned_file_id", nullable = false, updatable = false)
    private Long scannedFileId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "group_id")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VideoFileRole role = VideoFileRole.PRIMARY;

    @Column(name = "episode_index")
    private Integer episodeIndex;

    @Column(name = "sort_key", nullable = false)
    private String sortKey = "";

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "video_codec", length = 32)
    private String videoCodec;

    @Column(name = "audio_codec", length = 32)
    private String audioCodec;

    @Column
    private Long bitrate;

    @Column(length = 16)
    private String container;

    protected VideoFile() {
    }

    VideoFile(Long scannedFileId, Long itemId, VideoFileRole role, String sortSource) {
        this.scannedFileId = scannedFileId;
        this.itemId = itemId;
        this.role = role;
        this.sortKey = NaturalSortKey.of(sortSource);
    }

    public Long getId() { return id; }
    public Long getScannedFileId() { return scannedFileId; }
    public Long getItemId() { return itemId; }
    public Long getGroupId() { return groupId; }
    public VideoFileRole getRole() { return role; }
    public Integer getEpisodeIndex() { return episodeIndex; }
    public String getSortKey() { return sortKey; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getVideoCodec() { return videoCodec; }
    public String getAudioCodec() { return audioCodec; }

    void assignGroup(Long groupId, Integer episodeIndex) {
        this.groupId = groupId;
        this.episodeIndex = episodeIndex;
    }

    /** 由计划 05 的 ffprobe 探测结果回填。 */
    void applyProbe(Integer durationSeconds, Integer width, Integer height,
                    String videoCodec, String audioCodec, Long bitrate, String container) {
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.bitrate = bitrate;
        this.container = container;
    }
}
```

`src/main/java/com/mymedia/video/VideoFolder.java`：

```java
package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 视频库的目录树浏览视图。
 *
 * <p><b>这是派生索引，不是主模型。</b>视频域的主浏览方式是语义化的
 * （按电影 / 剧集 / 合集）；本表只让用户能按自己的目录组织方式导航，
 * 不承载元数据与观看进度。
 */
@Entity
@Table(name = "video_folder")
public class VideoFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "materialized_path", nullable = false)
    private String materializedPath;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "direct_item_count", nullable = false)
    private int directItemCount = 0;

    @Column(name = "total_item_count", nullable = false)
    private int totalItemCount = 0;

    protected VideoFolder() {
    }

    VideoFolder(Long libraryId, Long parentId, String parentPath, String name) {
        this.libraryId = libraryId;
        this.parentId = parentId;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);
        // 路径在获得 id 之前无法确定，先占位为父路径，插入后由 indexer 修正
        this.materializedPath = parentPath;
        this.depth = MaterializedPath.depthOf(parentPath);
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getParentId() { return parentId; }
    public String getMaterializedPath() { return materializedPath; }
    public int getDepth() { return depth; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
    public int getDirectItemCount() { return directItemCount; }
    public int getTotalItemCount() { return totalItemCount; }

    /**
     * 插入拿到 id 之后补全自身路径。物化路径包含自己的 id，
     * 因此必须在 INSERT 之后才能确定。
     */
    void finalizePath(String parentPath) {
        this.materializedPath = MaterializedPath.childOf(parentPath, this.id);
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    void setCounts(int direct, int total) {
        this.directItemCount = direct;
        this.totalItemCount = total;
    }
}
```

- [ ] **Step 6: 写仓储**

`src/main/java/com/mymedia/video/VideoItemRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoItemRepository extends JpaRepository<VideoItem, Long> {

    Page<VideoItem> findByLibraryIdIn(List<Long> libraryIds, Pageable pageable);

    Optional<VideoItem> findByLibraryIdAndTitle(Long libraryId, String title);

    List<VideoItem> findByFolderId(Long folderId);

    long countByFolderId(Long folderId);
}
```

`src/main/java/com/mymedia/video/VideoGroupRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoGroupRepository extends JpaRepository<VideoGroup, Long> {

    Optional<VideoGroup> findByItemIdAndGroupIndex(Long itemId, int groupIndex);

    List<VideoGroup> findByItemIdOrderBySortKey(Long itemId);
}
```

`src/main/java/com/mymedia/video/VideoFileRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoFileRepository extends JpaRepository<VideoFile, Long> {

    Optional<VideoFile> findByScannedFileId(Long scannedFileId);

    List<VideoFile> findByItemIdOrderBySortKey(Long itemId);

    List<VideoFile> findByGroupIdOrderByEpisodeIndex(Long groupId);
}
```

`src/main/java/com/mymedia/video/VideoFolderRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoFolderRepository extends JpaRepository<VideoFolder, Long> {

    Optional<VideoFolder> findByLibraryIdAndParentIdAndName(Long libraryId, Long parentId, String name);

    Optional<VideoFolder> findByLibraryIdAndParentIdIsNullAndName(Long libraryId, String name);

    List<VideoFolder> findByParentIdOrderBySortKey(Long parentId);

    List<VideoFolder> findByLibraryIdAndParentIdIsNullOrderBySortKey(Long libraryId);
}
```

- [ ] **Step 7: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V6__video_domain.sql src/main/java/com/mymedia/video src/test/java/com/mymedia/video
git commit -m "feat: 添加视频域表与实体

structure 是独立字段不由 item_type 推导——一部电影也可以是 GROUPED。
video_file.role 让 FLAT 条目也能有多版本与花絮。
域分区由复合外键强制，测试验证视频条目无法落进图片库。"
```

Expected: `EXIT=0`，`Tests run: 6, Failures: 0`

---

## Task 4: 视频文件名解析器

**Files:**
- Create: `src/main/java/com/mymedia/video/ParsedVideoName.java`
- Create: `src/main/java/com/mymedia/video/VideoFilenameParser.java`
- Test: `src/test/java/com/mymedia/video/VideoFilenameParserTest.java`

**Interfaces:**
- Consumes: 无（纯逻辑）
- Produces:
  - `record ParsedVideoName(String title, Integer season, Integer episode, Integer year, String quality)`（package-private）
  - `class VideoFilenameParser`（package-private）— `static ParsedVideoName parse(String relativePath)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoFilenameParserTest.java`：

```java
package com.mymedia.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFilenameParserTest {

    @Test
    void parsesStandardSeasonEpisodePattern() {
        ParsedVideoName parsed = VideoFilenameParser.parse("进击的巨人/S01/S01E05.mkv");

        assertThat(parsed.season()).isEqualTo(1);
        assertThat(parsed.episode()).isEqualTo(5);
    }

    @Test
    void parsesLowercaseSeasonEpisode() {
        ParsedVideoName parsed = VideoFilenameParser.parse("show/s2e13.mp4");

        assertThat(parsed.season()).isEqualTo(2);
        assertThat(parsed.episode()).isEqualTo(13);
    }

    @Test
    void parsesSeasonFromDirectoryAndEpisodeFromFile() {
        ParsedVideoName parsed = VideoFilenameParser.parse("进击的巨人/Season 3/E07.mkv");

        assertThat(parsed.season()).isEqualTo(3);
        assertThat(parsed.episode()).isEqualTo(7);
    }

    @Test
    void parsesChineseSeasonDirectory() {
        ParsedVideoName parsed = VideoFilenameParser.parse("某番剧/第2季/第11话.mkv");

        assertThat(parsed.season()).isEqualTo(2);
        assertThat(parsed.episode()).isEqualTo(11);
    }

    @Test
    void parsesBracketedEpisodeNumber() {
        // 字幕组常用格式
        ParsedVideoName parsed = VideoFilenameParser.parse("[字幕组] 某番 [08][1080p].mkv");

        assertThat(parsed.episode()).isEqualTo(8);
    }

    @Test
    void parsesMovieYear() {
        ParsedVideoName parsed = VideoFilenameParser.parse("电影/黑客帝国 (1999).mkv");

        assertThat(parsed.title()).isEqualTo("黑客帝国");
        assertThat(parsed.year()).isEqualTo(1999);
        assertThat(parsed.season()).isNull();
        assertThat(parsed.episode()).isNull();
    }

    @Test
    void parsesDotSeparatedMovieName() {
        ParsedVideoName parsed = VideoFilenameParser.parse("The.Matrix.1999.1080p.BluRay.mkv");

        assertThat(parsed.title()).isEqualTo("The Matrix");
        assertThat(parsed.year()).isEqualTo(1999);
        assertThat(parsed.quality()).isEqualTo("1080p");
    }

    @Test
    void stripsReleaseGroupTags() {
        ParsedVideoName parsed = VideoFilenameParser.parse("[SubGroup] 作品名 [1080p][BDRip].mkv");

        assertThat(parsed.title()).isEqualTo("作品名");
    }

    @Test
    void titleFallsBackToFilenameWhenNothingMatches() {
        // 自制内容、录屏等，没有任何可识别模式。绝不能因此报错或返回空标题。
        ParsedVideoName parsed = VideoFilenameParser.parse("随手录的一段.mkv");

        assertThat(parsed.title()).isEqualTo("随手录的一段");
        assertThat(parsed.season()).isNull();
        assertThat(parsed.episode()).isNull();
        assertThat(parsed.year()).isNull();
    }

    @Test
    void doesNotMistakeResolutionForYear() {
        // 1080 与 2160 不是年份
        ParsedVideoName parsed = VideoFilenameParser.parse("片子.2160p.mkv");

        assertThat(parsed.year()).isNull();
        assertThat(parsed.quality()).isEqualTo("2160p");
    }

    @Test
    void yearMustBePlausible() {
        // 1234 在合法年份范围外，不应被当成年份
        ParsedVideoName parsed = VideoFilenameParser.parse("编号1234的片子.mkv");

        assertThat(parsed.year()).isNull();
    }

    @Test
    void handlesPathWithoutDirectory() {
        ParsedVideoName parsed = VideoFilenameParser.parse("单独一个文件.mp4");

        assertThat(parsed.title()).isEqualTo("单独一个文件");
    }

    @Test
    void episodeZeroIsValid() {
        // 第 0 话（前导集）是真实存在的
        ParsedVideoName parsed = VideoFilenameParser.parse("番/S01E00.mkv");

        assertThat(parsed.episode()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoFilenameParserTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写解析结果**

`src/main/java/com/mymedia/video/ParsedVideoName.java`：

```java
package com.mymedia.video;

/**
 * 从文件路径推断出的元数据。
 *
 * <p>{@code title} <b>永远非空</b>——解析不出任何模式时回落到去掉扩展名的文件名。
 * 这是 spec 7.2 规则 1 的落实：没有刮削也完全可用，扫描完成的瞬间每个条目就有标题。
 *
 * @param season  季号，无法判定时为 null
 * @param episode 集号，无法判定时为 null
 * @param year    发行年份，无法判定时为 null
 * @param quality 画质标记（如 1080p），无法判定时为 null
 */
record ParsedVideoName(String title, Integer season, Integer episode, Integer year, String quality) {
}
```

- [ ] **Step 4: 实现解析器**

`src/main/java/com/mymedia/video/VideoFilenameParser.java`：

```java
package com.mymedia.video;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从文件路径推断标题、季号、集号、年份与画质。
 *
 * <p>分层策略：先从<b>目录名</b>取季号（{@code Season 3} / {@code 第2季} / {@code S03}），
 * 再从<b>文件名</b>取集号，最后清洗出标题。任何一层失败都不影响其他层——
 * 解析器<b>永不抛异常</b>，最差情况是返回去掉扩展名的原文件名作为标题。
 *
 * <p>这条「永不失败」的性质是 spec 7.2 规则 1 的基础：刮削是可选增强，
 * 没有它系统也必须完全可用。
 */
final class VideoFilenameParser {

    /** S01E05 / s1e13 */
    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)s(\\d{1,3})[\\s._-]*e(\\d{1,4})");

    /** 目录名中的季号：Season 3 / 第2季 / S03 */
    private static final Pattern SEASON_IN_DIR =
            Pattern.compile("(?i)(?:season[\\s._-]*|第\\s*|s)(\\d{1,3})\\s*(?:季)?");

    /** 文件名中的独立集号：E07 / 第11话 / [08] */
    private static final Pattern EPISODE_ONLY =
            Pattern.compile("(?i)(?:\\be(\\d{1,4})\\b|第\\s*(\\d{1,4})\\s*[话話集]|\\[(\\d{1,4})\\])");

    /** 圆括号或独立词形式的年份 */
    private static final Pattern YEAR = Pattern.compile("(?<![\\d])(19\\d{2}|20\\d{2})(?![\\d])");

    /** 画质标记 */
    private static final Pattern QUALITY = Pattern.compile("(?i)\\b(2160p|1080p|720p|480p|4k)\\b");

    /** 方括号标签：字幕组、压制组、画质标记等 */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)");

    private VideoFilenameParser() {
    }

    static ParsedVideoName parse(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
        String directories = lastSlash < 0 ? "" : normalized.substring(0, lastSlash);

        String baseName = stripExtension(fileName);

        Integer season = null;
        Integer episode = null;

        // 第一优先：文件名里的 SxxExx，季集一次拿全
        Matcher se = SEASON_EPISODE.matcher(baseName);
        if (se.find()) {
            season = parseIntOrNull(se.group(1));
            episode = parseIntOrNull(se.group(2));
        }

        // 季号退而求其次从目录名取
        if (season == null && !directories.isEmpty()) {
            season = seasonFromDirectories(directories);
        }

        // 集号退而求其次从文件名的独立模式取
        if (episode == null) {
            episode = episodeFrom(baseName);
        }

        Integer year = yearFrom(baseName);
        String quality = qualityFrom(baseName);
        String title = cleanTitle(baseName, directories, season, episode);

        return new ParsedVideoName(title, season, episode, year, quality);
    }

    private static Integer seasonFromDirectories(String directories) {
        String[] segments = directories.split("/");
        // 从最靠近文件的目录往外找，越近的越可能是季目录
        for (int i = segments.length - 1; i >= 0; i--) {
            Matcher m = SEASON_IN_DIR.matcher(segments[i]);
            if (m.find()) {
                Integer value = parseIntOrNull(m.group(1));
                if (value != null && value <= 100) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Integer episodeFrom(String baseName) {
        Matcher m = EPISODE_ONLY.matcher(baseName);
        while (m.find()) {
            for (int g = 1; g <= 3; g++) {
                if (m.group(g) != null) {
                    return parseIntOrNull(m.group(g));
                }
            }
        }
        return null;
    }

    private static Integer yearFrom(String baseName) {
        // 先去掉画质标记，避免 2160p 里的 2160 之类的干扰
        String withoutQuality = QUALITY.matcher(baseName).replaceAll(" ");
        Matcher m = YEAR.matcher(withoutQuality);
        if (m.find()) {
            return parseIntOrNull(m.group(1));
        }
        return null;
    }

    private static String qualityFrom(String baseName) {
        Matcher m = QUALITY.matcher(baseName);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /**
     * 清洗标题：去掉方括号标签、季集标记、年份、画质、发布组噪音，
     * 把点与下划线还原成空格。
     *
     * <p>清洗后若为空，<b>回落到目录名，再回落到原文件名</b>——标题永远非空。
     */
    private static String cleanTitle(String baseName, String directories,
                                     Integer season, Integer episode) {
        String work = BRACKET_TAG.matcher(baseName).replaceAll(" ");
        work = SEASON_EPISODE.matcher(work).replaceAll(" ");
        work = EPISODE_ONLY.matcher(work).replaceAll(" ");
        work = QUALITY.matcher(work).replaceAll(" ");
        work = YEAR.matcher(work).replaceAll(" ");
        work = work.replaceAll("(?i)\\b(bluray|bdrip|webrip|web-dl|hdtv|x264|x265|h264|h265|hevc|aac|flac)\\b", " ");
        work = work.replaceAll("[._]+", " ");
        work = work.replaceAll("\\s{2,}", " ").trim();
        work = work.replaceAll("^[\\s\\-–—]+|[\\s\\-–—]+$", "").trim();

        if (!work.isEmpty()) {
            return work;
        }

        // 文件名被清空了（例如整个名字就是 "S01E05"），用最靠近的非季目录名
        if (!directories.isEmpty()) {
            String[] segments = directories.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                String candidate = segments[i].trim();
                if (!candidate.isEmpty() && !SEASON_IN_DIR.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }

        // 最后的兜底：原文件名去扩展名。永不返回空标题。
        return baseName;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoFilenameParserTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|expected|but was" t.log | head -20
```

Expected: `EXIT=0`，`Tests run: 13, Failures: 0`

**若个别用例失败**：文件名解析是启发式的，正则需要按实际失败的用例微调。**调整正则而不是删改测试**——每个测试用例都对应一种真实存在的命名习惯。

- [ ] **Step 6: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/video src/test/java/com/mymedia/video/VideoFilenameParserTest.java
git commit -m "feat: 添加视频文件名解析器

分层策略：目录名取季号、文件名取集号、清洗出标题。
永不抛异常、永不返回空标题——这是「没有刮削也完全可用」的基础。"
```

---

## Task 5: 语义层构建（实现扫描 SPI）

**Files:**
- Create: `src/main/java/com/mymedia/video/VideoContentBuilder.java`
- Create: `src/main/java/com/mymedia/video/event/VideoItemCreated.java`
- Test: `src/test/java/com/mymedia/video/VideoContentBuilderTest.java`

**Interfaces:**
- Consumes: `LibraryContentBuilder`、`ScannedFileDiscovered`、`ScannedFileVanished`、`ScannedFileQueryService`（计划 02 Task 1、7）、`VideoFilenameParser`（Task 4）、各仓储（Task 3）
- Produces:
  - `public record VideoItemCreated(Long itemId, Long libraryId, String title)`
  - `VideoContentBuilder implements LibraryContentBuilder`（package-private，Spring bean）

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoContentBuilderTest.java`：

```java
package com.mymedia.video;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoContentBuilderTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    VideoCatalogService catalogService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
    }

    private void writeMedia(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content-" + relative);
    }

    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
    }

    @Test
    void movieBecomesFlatItemWithOneFile() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/黑客帝国 (1999).mkv");

        scan(library.getId());

        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        VideoItem item = items.getFirst();
        assertThat(item.getTitle()).isEqualTo("黑客帝国");
        assertThat(item.getStructure()).isEqualTo(VideoStructure.FLAT);
        assertThat(catalogService.filesOf(item.getId())).hasSize(1);
    }

    @Test
    void seriesEpisodesGroupIntoOneItemWithSeasons() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("番剧/进击的巨人/S01E01.mkv");
        writeMedia("番剧/进击的巨人/S01E02.mkv");
        writeMedia("番剧/进击的巨人/S02E01.mkv");

        scan(library.getId());

        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);

        VideoItem item = items.getFirst();
        assertThat(item.getTitle()).isEqualTo("进击的巨人");
        // 出现季号即提升为 GROUPED
        assertThat(item.getStructure()).isEqualTo(VideoStructure.GROUPED);
        assertThat(catalogService.groupsOf(item.getId())).hasSize(2);
        assertThat(catalogService.filesOf(item.getId())).hasSize(3);
    }

    @Test
    void episodesAreOrderedNaturally() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("番剧/某番/S01E01.mkv");
        writeMedia("番剧/某番/S01E10.mkv");
        writeMedia("番剧/某番/S01E02.mkv");

        scan(library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        Long seasonOne = catalogService.groupsOf(item.getId()).getFirst().getId();

        assertThat(catalogService.episodesOf(seasonOne))
                .extracting(VideoFile::getEpisodeIndex)
                .containsExactly(1, 2, 10);
    }

    @Test
    void unparsableFileStillBecomesAnItem() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("随手录的一段.mkv");

        scan(library.getId());

        // 解析不出任何模式也必须可用 —— 标题回落到文件名
        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getTitle()).isEqualTo("随手录的一段");
        assertThat(items.getFirst().getItemType()).isEqualTo(VideoItemType.SINGLE_VIDEO);
    }

    @Test
    void rescanIsIdempotent() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");

        scan(library.getId());
        scan(library.getId());
        scan(library.getId());

        // 重复扫描不应产生重复条目
        assertThat(catalogService.findByLibrary(library.getId())).hasSize(1);
    }

    @Test
    void renamingFileKeepsTheSameVideoFileRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/旧名.mkv");
        scan(library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        Long videoFileId = catalogService.filesOf(item.getId()).getFirst().getId();

        Files.move(root.resolve("电影/旧名.mkv"), root.resolve("电影/新名.mkv"));
        scan(library.getId());

        // 改名走物理层，语义层通过外键跟随 —— video_file 行不变
        assertThat(catalogService.filesOf(item.getId()))
                .extracting(VideoFile::getId)
                .containsExactly(videoFileId);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoContentBuilderTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写事件**

`src/main/java/com/mymedia/video/event/VideoItemCreated.java`：

```java
package com.mymedia.video.event;

/**
 * 新建了一个视频条目。
 *
 * <p>由 {@code metadata} 模块订阅去刮削、{@code preview} 模块订阅去生成封面。
 * {@code video} 模块不知道它们的存在。
 */
public record VideoItemCreated(Long itemId, Long libraryId, String title) {
}
```

- [ ] **Step 4: 写查询服务**

`src/main/java/com/mymedia/video/VideoCatalogService.java`：

```java
package com.mymedia.video;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code video} 模块对外暴露的条目查询能力。
 */
@Service
public class VideoCatalogService {

    private final VideoItemRepository itemRepository;
    private final VideoGroupRepository groupRepository;
    private final VideoFileRepository fileRepository;

    VideoCatalogService(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional(readOnly = true)
    public VideoItem getItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("找不到视频条目 id=" + itemId));
    }

    @Transactional(readOnly = true)
    public List<VideoItem> findByLibrary(Long libraryId) {
        return itemRepository.findByLibraryIdIn(List.of(libraryId),
                org.springframework.data.domain.Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<VideoGroup> groupsOf(Long itemId) {
        return groupRepository.findByItemIdOrderBySortKey(itemId);
    }

    @Transactional(readOnly = true)
    public List<VideoFile> filesOf(Long itemId) {
        return fileRepository.findByItemIdOrderBySortKey(itemId);
    }

    @Transactional(readOnly = true)
    public List<VideoFile> episodesOf(Long groupId) {
        return fileRepository.findByGroupIdOrderByEpisodeIndex(groupId);
    }

    @Transactional(readOnly = true)
    public VideoFile getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("找不到视频文件 id=" + fileId));
    }
}
```

- [ ] **Step 5: 实现 SPI**

`src/main/java/com/mymedia/video/VideoContentBuilder.java`：

```java
package com.mymedia.video;

import com.mymedia.library.LibraryDomain;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import com.mymedia.scan.spi.MediaKind;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把扫描发现的视频文件构建成语义结构。
 *
 * <p>归并规则：
 * <ol>
 *   <li>解析出季或集号 → 按<b>标题</b>归并到同一个条目，条目提升为 {@code GROUPED}</li>
 *   <li>解析出年份、无季集 → {@code MOVIE}，{@code FLAT}</li>
 *   <li>什么都没解析出来 → {@code SINGLE_VIDEO}，{@code FLAT}，标题即文件名</li>
 * </ol>
 *
 * <p>整个过程<b>幂等</b>：重复扫描同一批文件不产生重复条目。
 */
@Component
class VideoContentBuilder implements LibraryContentBuilder {

    private static final Logger log = LoggerFactory.getLogger(VideoContentBuilder.class);

    private final VideoItemRepository itemRepository;
    private final VideoGroupRepository groupRepository;
    private final VideoFileRepository fileRepository;
    private final VideoFolderIndexer folderIndexer;
    private final ApplicationEventPublisher events;

    VideoContentBuilder(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository,
                        VideoFolderIndexer folderIndexer,
                        ApplicationEventPublisher events) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
        this.folderIndexer = folderIndexer;
        this.events = events;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO;
    }

    @Override
    @Transactional
    public void onFileDiscovered(ScannedFileDiscovered event) {
        if (event.kind() != MediaKind.VIDEO) {
            return;
        }
        // 幂等保护：同一个物理文件只建一次语义记录
        if (fileRepository.findByScannedFileId(event.scannedFileId()).isPresent()) {
            return;
        }

        ParsedVideoName parsed = VideoFilenameParser.parse(event.relativePath());
        VideoItem item = findOrCreateItem(event.libraryId(), parsed, event.relativePath());

        VideoFile file = new VideoFile(
                event.scannedFileId(), item.getId(), VideoFileRole.PRIMARY, event.relativePath());

        if (parsed.season() != null || parsed.episode() != null) {
            if (item.getStructure() != VideoStructure.GROUPED) {
                item.promoteToGrouped();
            }
            int seasonIndex = parsed.season() == null ? 1 : parsed.season();
            VideoGroup group = findOrCreateGroup(item.getId(), seasonIndex);
            file.assignGroup(group.getId(), parsed.episode());
        }

        fileRepository.saveAndFlush(file);
        folderIndexer.attachItemToFolder(event.libraryId(), event.relativePath(), item);
    }

    @Override
    @Transactional
    public void onFileVanished(ScannedFileVanished event) {
        // 语义层不做任何删除：物理层已标记 MISSING，
        // 条目仍在，用户的进度、收藏、手工元数据全部保留。
        // 播放时由 VideoStreamService 检查物理状态并返回明确错误。
        log.debug("视频文件不可用: {}", event.relativePath());
    }

    private VideoItem findOrCreateItem(Long libraryId, ParsedVideoName parsed, String relativePath) {
        return itemRepository.findByLibraryIdAndTitle(libraryId, parsed.title())
                .orElseGet(() -> {
                    VideoItemType type = inferType(parsed);
                    VideoStructure structure = (parsed.season() != null || parsed.episode() != null)
                            ? VideoStructure.GROUPED
                            : VideoStructure.FLAT;
                    VideoItem created = itemRepository.saveAndFlush(
                            new VideoItem(libraryId, type, structure, parsed.title()));
                    log.info("新建视频条目 id={} title={} type={} from={}",
                            created.getId(), parsed.title(), type, relativePath);
                    events.publishEvent(new VideoItemCreated(
                            created.getId(), libraryId, parsed.title()));
                    return created;
                });
    }

    private VideoGroup findOrCreateGroup(Long itemId, int seasonIndex) {
        return groupRepository.findByItemIdAndGroupIndex(itemId, seasonIndex)
                .orElseGet(() -> groupRepository.saveAndFlush(
                        new VideoGroup(itemId, seasonIndex, "第 " + seasonIndex + " 季")));
    }

    private static VideoItemType inferType(ParsedVideoName parsed) {
        if (parsed.season() != null || parsed.episode() != null) {
            return VideoItemType.SERIES;
        }
        if (parsed.year() != null) {
            return VideoItemType.MOVIE;
        }
        return VideoItemType.SINGLE_VIDEO;
    }
}
```

- [ ] **Step 6: 把 SPI 接到扫描事件上**

在 `scan` 模块新建 `src/main/java/com/mymedia/scan/ContentBuilderDispatcher.java`：

```java
package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把扫描事件按媒体库的 domain 分派给对应的领域构建器。
 *
 * <p>{@code scan} 模块通过 Spring 注入拿到所有 {@link LibraryContentBuilder} 实现，
 * <b>不需要知道具体有哪些实现类</b>——依赖方向由 SPI 倒置。
 */
@Component
class ContentBuilderDispatcher {

    private final List<LibraryContentBuilder> builders;
    private final LibraryService libraryService;

    ContentBuilderDispatcher(List<LibraryContentBuilder> builders, LibraryService libraryService) {
        this.builders = builders;
        this.libraryService = libraryService;
    }

    @EventListener
    void on(ScannedFileDiscovered event) {
        var domain = libraryService.getById(event.libraryId()).getDomain();
        for (LibraryContentBuilder builder : builders) {
            if (builder.supports(domain)) {
                builder.onFileDiscovered(event);
            }
        }
    }

    @EventListener
    void on(ScannedFileVanished event) {
        var domain = libraryService.getById(event.libraryId()).getDomain();
        for (LibraryContentBuilder builder : builders) {
            if (builder.supports(domain)) {
                builder.onFileVanished(event);
            }
        }
    }
}
```

- [ ] **Step 7: 运行测试确认通过并提交**

先完成 Task 6 的 `VideoFolderIndexer` 再运行——本任务依赖它。

**若急于验证本任务**，可先创建一个空实现占位，Task 6 再补全：

```java
// 临时占位，Task 6 会替换为完整实现
package com.mymedia.video;

import org.springframework.stereotype.Service;

@Service
class VideoFolderIndexer {

    void attachItemToFolder(Long libraryId, String relativePath, VideoItem item) {
        // Task 6 实现
    }
}
```

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoContentBuilderTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia/video/VideoContentBuilderTest.java
git commit -m "feat: 实现视频域语义层构建

按标题归并条目、出现季集即提升为 GROUPED、幂等重扫。
分派器通过 Spring 注入拿到全部 SPI 实现，scan 不知道具体实现类。"
```

Expected: `EXIT=0`，`Tests run: 6, Failures: 0`

---

## Task 6: 目录树索引与浏览

**Files:**
- Create: `src/main/java/com/mymedia/video/VideoFolderIndexer.java`（替换 Task 5 的占位）
- Create: `src/main/java/com/mymedia/video/VideoBrowseService.java`
- Create: `src/main/java/com/mymedia/video/web/VideoBrowseController.java`
- Create: `src/main/java/com/mymedia/video/web/VideoBrowseDto.java`
- Test: `src/test/java/com/mymedia/video/VideoFolderIndexerTest.java`

**Interfaces:**
- Consumes: `MaterializedPath`（Task 2）、`VideoFolder`、`VideoFolderRepository`（Task 3）
- Produces:
  - `class VideoFolderIndexer`（package-private）— `void attachItemToFolder(Long libraryId, String relativePath, VideoItem item)`
  - `public class VideoBrowseService` — `public List<VideoFolder> childFolders(Long libraryId, Long folderId)`、`public List<VideoItem> itemsIn(Long folderId)`、`public List<VideoFolder> breadcrumb(Long folderId)`
  - `GET /api/video/browse?libraryId=&folderId=`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoFolderIndexerTest.java`：

```java
package com.mymedia.video;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFolderIndexerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    VideoBrowseService browseService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
    }

    private void writeMedia(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "c-" + relative);
    }

    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
    }

    @Test
    void buildsFolderTreeMirroringDirectories() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/科幻/黑客帝国.mkv");
        writeMedia("电影/动作/谍影重重.mkv");
        writeMedia("番剧/进击的巨人/S01E01.mkv");

        scan(library.getId());

        List<VideoFolder> topLevel = browseService.childFolders(library.getId(), null);
        assertThat(topLevel).extracting(VideoFolder::getName)
                .containsExactlyInAnyOrder("电影", "番剧");

        VideoFolder movies = topLevel.stream()
                .filter(f -> f.getName().equals("电影")).findFirst().orElseThrow();
        assertThat(browseService.childFolders(library.getId(), movies.getId()))
                .extracting(VideoFolder::getName)
                .containsExactlyInAnyOrder("科幻", "动作");
    }

    @Test
    void materializedPathReflectsHierarchy() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("a/b/c/片子.mkv");

        scan(library.getId());

        VideoFolder a = browseService.childFolders(library.getId(), null).getFirst();
        VideoFolder b = browseService.childFolders(library.getId(), a.getId()).getFirst();
        VideoFolder c = browseService.childFolders(library.getId(), b.getId()).getFirst();

        assertThat(a.getDepth()).isEqualTo(1);
        assertThat(b.getDepth()).isEqualTo(2);
        assertThat(c.getDepth()).isEqualTo(3);
        assertThat(c.getMaterializedPath()).startsWith(b.getMaterializedPath());
        assertThat(b.getMaterializedPath()).startsWith(a.getMaterializedPath());
    }

    @Test
    void breadcrumbResolvesAncestorsWithoutRecursiveQuery() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("一层/二层/三层/片子.mkv");

        scan(library.getId());

        VideoFolder l1 = browseService.childFolders(library.getId(), null).getFirst();
        VideoFolder l2 = browseService.childFolders(library.getId(), l1.getId()).getFirst();
        VideoFolder l3 = browseService.childFolders(library.getId(), l2.getId()).getFirst();

        assertThat(browseService.breadcrumb(l3.getId()))
                .extracting(VideoFolder::getName)
                .containsExactly("一层", "二层", "三层");
    }

    @Test
    void itemsAreAttachedToTheirDirectory() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");

        scan(library.getId());

        VideoFolder movies = browseService.childFolders(library.getId(), null).getFirst();
        assertThat(browseService.itemsIn(movies.getId()))
                .extracting(VideoItem::getTitle)
                .containsExactly("片子");
    }

    @Test
    void foldersAreSortedNaturally() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("第10季/a.mkv");
        writeMedia("第2季/a.mkv");
        writeMedia("第1季/a.mkv");

        scan(library.getId());

        assertThat(browseService.childFolders(library.getId(), null))
                .extracting(VideoFolder::getName)
                .containsExactly("第1季", "第2季", "第10季");
    }

    @Test
    void rescanDoesNotDuplicateFolders() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");

        scan(library.getId());
        scan(library.getId());

        assertThat(browseService.childFolders(library.getId(), null)).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoFolderIndexerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -8
```

- [ ] **Step 3: 实现目录树索引器**

`src/main/java/com/mymedia/video/VideoFolderIndexer.java`（**替换 Task 5 的占位实现**）：

```java
package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护视频库的目录树浏览视图。
 *
 * <p>把文件的相对路径按分隔符拆开，逐层查找或创建 {@link VideoFolder}，
 * 最后把条目挂到最深一层目录上。
 *
 * <p>物化路径包含节点自身的 id，因此必须<b>先 INSERT 拿到 id、再补全路径</b>，
 * 这就是 {@link VideoFolder#finalizePath} 存在的原因。
 */
@Service
class VideoFolderIndexer {

    private final VideoFolderRepository folderRepository;

    VideoFolderIndexer(VideoFolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    @Transactional
    void attachItemToFolder(Long libraryId, String relativePath, VideoItem item) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            // 文件直接躺在库根目录，不属于任何子目录
            return;
        }
        String[] segments = relativePath.substring(0, lastSlash).split("/");

        Long parentId = null;
        String parentPath = MaterializedPath.rootPath();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            VideoFolder folder = findOrCreate(libraryId, parentId, parentPath, segment);
            parentId = folder.getId();
            parentPath = folder.getMaterializedPath();
        }

        if (parentId != null && item.getFolderId() == null) {
            item.assignFolder(parentId);
        }
    }

    private VideoFolder findOrCreate(Long libraryId, Long parentId, String parentPath, String name) {
        var existing = parentId == null
                ? folderRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : folderRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);

        if (existing.isPresent()) {
            return existing.get();
        }

        VideoFolder created = folderRepository.saveAndFlush(
                new VideoFolder(libraryId, parentId, parentPath, name));
        // 路径含自身 id，只能在拿到 id 之后补全
        created.finalizePath(parentPath);
        return folderRepository.saveAndFlush(created);
    }
}
```

- [ ] **Step 4: 实现浏览服务**

`src/main/java/com/mymedia/video/VideoBrowseService.java`：

```java
package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 视频库的目录树浏览（次要视图）。
 *
 * <p>主浏览方式是语义化的（见 {@link VideoCatalogService}）；本服务让用户
 * 能按自己的目录组织方式导航。
 */
@Service
public class VideoBrowseService {

    private final VideoFolderRepository folderRepository;
    private final VideoItemRepository itemRepository;

    VideoBrowseService(VideoFolderRepository folderRepository,
                       VideoItemRepository itemRepository) {
        this.folderRepository = folderRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<VideoFolder> childFolders(Long libraryId, Long folderId) {
        return folderId == null
                ? folderRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId)
                : folderRepository.findByParentIdOrderBySortKey(folderId);
    }

    @Transactional(readOnly = true)
    public List<VideoItem> itemsIn(Long folderId) {
        return itemRepository.findByFolderId(folderId);
    }

    @Transactional(readOnly = true)
    public VideoFolder getFolder(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new NotFoundException("找不到目录 id=" + folderId));
    }

    /**
     * 面包屑导航。
     *
     * <p>直接从物化路径解析出全部祖先 id，<b>一次查询搞定，不需要递归</b>——
     * 这正是存物化路径的主要收益。
     */
    @Transactional(readOnly = true)
    public List<VideoFolder> breadcrumb(Long folderId) {
        VideoFolder folder = getFolder(folderId);
        List<Long> ancestorIds = MaterializedPath.ancestorIds(folder.getMaterializedPath());
        List<VideoFolder> folders = folderRepository.findAllById(ancestorIds);
        // findAllById 不保证顺序，按物化路径中的顺序重排
        return ancestorIds.stream()
                .map(id -> folders.stream().filter(f -> f.getId().equals(id)).findFirst().orElseThrow())
                .toList();
    }
}
```

- [ ] **Step 5: 实现浏览端点**

`src/main/java/com/mymedia/video/web/VideoBrowseDto.java`：

```java
package com.mymedia.video.web;

import com.mymedia.video.VideoFolder;
import com.mymedia.video.VideoItem;

import java.util.List;

public final class VideoBrowseDto {

    private VideoBrowseDto() {
    }

    public record FolderNode(Long id, String name, int depth, int totalItemCount) {

        static FolderNode from(VideoFolder folder) {
            return new FolderNode(folder.getId(), folder.getName(),
                    folder.getDepth(), folder.getTotalItemCount());
        }
    }

    public record ItemNode(Long id, String title, String itemType, String structure) {

        static ItemNode from(VideoItem item) {
            return new ItemNode(item.getId(), item.getTitle(),
                    item.getItemType().name(), item.getStructure().name());
        }
    }

    public record BrowseResponse(
            List<FolderNode> breadcrumb,
            List<FolderNode> folders,
            List<ItemNode> items) {
    }
}
```

`src/main/java/com/mymedia/video/web/VideoBrowseController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoBrowseService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video/browse")
class VideoBrowseController {

    private final VideoBrowseService browseService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoBrowseController(VideoBrowseService browseService,
                          LibraryAccessService accessService,
                          UserQueryService userQueryService) {
        this.browseService = browseService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    VideoBrowseDto.BrowseResponse browse(@AuthenticationPrincipal UserDetails principal,
                                         @RequestParam Long libraryId,
                                         @RequestParam(required = false) Long folderId) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        if (!accessService.canAccess(userId, libraryId)) {
            // 404 而非 403：不向无权访问者泄露资源是否存在
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }

        List<VideoBrowseDto.FolderNode> breadcrumb = folderId == null
                ? List.of()
                : browseService.breadcrumb(folderId).stream()
                        .map(VideoBrowseDto.FolderNode::from).toList();

        return new VideoBrowseDto.BrowseResponse(
                breadcrumb,
                browseService.childFolders(libraryId, folderId).stream()
                        .map(VideoBrowseDto.FolderNode::from).toList(),
                folderId == null
                        ? List.of()
                        : browseService.itemsIn(folderId).stream()
                                .map(VideoBrowseDto.ItemNode::from).toList());
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoFolderIndexerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/video src/test/java/com/mymedia/video/VideoFolderIndexerTest.java
git commit -m "feat: 添加视频目录树索引与浏览

物化路径含自身 id，必须先 INSERT 拿 id 再补全路径。
面包屑直接解析路径，一次查询搞定，不需要递归。"
```

Expected: `EXIT=0`，`Tests run: 6, Failures: 0`

---

## Task 7: Range 请求解析器

本项目最核心的技术展示点。

**Files:**
- Create: `src/main/java/com/mymedia/video/range/RangeResolution.java`
- Create: `src/main/java/com/mymedia/video/range/RangeParser.java`
- Test: `src/test/java/com/mymedia/video/range/RangeParserTest.java`

**Interfaces:**
- Consumes: 无（纯逻辑）
- Produces:
  - `public sealed interface RangeResolution` — 实现 `Full(long length)`、`Partial(long start, long endInclusive, long totalLength)`、`Unsatisfiable(long totalLength)`
  - `public final class RangeParser` — `public static RangeResolution resolve(String rangeHeader, long fileLength)`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/range/RangeParserTest.java`：

```java
package com.mymedia.video.range;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RangeParserTest {

    private static final long LENGTH = 1000L;

    @Test
    void nullHeaderMeansFullContent() {
        assertThat(RangeParser.resolve(null, LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void blankHeaderMeansFullContent() {
        assertThat(RangeParser.resolve("   ", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void closedRange() {
        assertThat(RangeParser.resolve("bytes=0-499", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 499, LENGTH));
    }

    @Test
    void openEndedRangeExtendsToEnd() {
        assertThat(RangeParser.resolve("bytes=500-", LENGTH))
                .isEqualTo(new RangeResolution.Partial(500, 999, LENGTH));
    }

    @Test
    void suffixRangeCountsFromEnd() {
        // bytes=-500 表示"最后 500 字节"，不是"从 0 到 500"
        assertThat(RangeParser.resolve("bytes=-500", LENGTH))
                .isEqualTo(new RangeResolution.Partial(500, 999, LENGTH));
    }

    @Test
    void suffixLongerThanFileClampsToWholeFile() {
        assertThat(RangeParser.resolve("bytes=-5000", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 999, LENGTH));
    }

    @Test
    void endBeyondFileIsClamped() {
        assertThat(RangeParser.resolve("bytes=900-5000", LENGTH))
                .isEqualTo(new RangeResolution.Partial(900, 999, LENGTH));
    }

    @Test
    void startAtOrBeyondLengthIsUnsatisfiable() {
        assertThat(RangeParser.resolve("bytes=1000-", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
        assertThat(RangeParser.resolve("bytes=5000-6000", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
    }

    @Test
    void startGreaterThanEndIsUnsatisfiable() {
        assertThat(RangeParser.resolve("bytes=500-100", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
    }

    @Test
    void multipleRangesReturnTheirUnion() {
        // spec 7.3 的决策：不实现 multipart/byteranges，返回覆盖全部区间的并集。
        // 浏览器的 <video> 元素不会发送多重 Range。
        assertThat(RangeParser.resolve("bytes=0-99,200-299", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 299, LENGTH));
    }

    @Test
    void unsupportedUnitIsIgnored() {
        // RFC 9110：无法识别的 range unit 应当忽略 Range 头，返回完整内容
        assertThat(RangeParser.resolve("items=0-10", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void malformedHeaderIsIgnored() {
        assertThat(RangeParser.resolve("bytes=abc-def", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=-", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void zeroLengthFileIsAlwaysUnsatisfiableForAnyRange() {
        assertThat(RangeParser.resolve("bytes=0-", 0))
                .isEqualTo(new RangeResolution.Unsatisfiable(0));
    }

    @Test
    void singleByteRange() {
        assertThat(RangeParser.resolve("bytes=0-0", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 0, LENGTH));
    }

    @Test
    void whitespaceIsTolerated() {
        assertThat(RangeParser.resolve("bytes = 0 - 499 ", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 499, LENGTH));
    }

    @Test
    void contentLengthOfPartialIsInclusive() {
        RangeResolution.Partial partial =
                (RangeResolution.Partial) RangeParser.resolve("bytes=0-499", LENGTH);

        // Range 的两端都是闭区间，长度是 end - start + 1
        assertThat(partial.contentLength()).isEqualTo(500L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=RangeParserTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写解析结果类型**

`src/main/java/com/mymedia/video/range/RangeResolution.java`：

```java
package com.mymedia.video.range;

/**
 * Range 头的解析结果，三种互斥情形。
 *
 * <p>用 sealed interface 而非「返回可能为 null 的 Range 对象」：
 * 调用方在 switch 上必须穷尽三种情形，漏掉任何一种都编译不过。
 */
public sealed interface RangeResolution {

    /** 无 Range 头、或 Range 头无法识别 —— 返回 200 与完整内容。 */
    record Full(long length) implements RangeResolution {
    }

    /** 有效的部分请求 —— 返回 206。两端均为闭区间。 */
    record Partial(long start, long endInclusive, long totalLength) implements RangeResolution {

        /** 闭区间的长度是 end - start + 1。 */
        public long contentLength() {
            return endInclusive - start + 1;
        }

        /** {@code Content-Range} 响应头的值。 */
        public String contentRangeHeader() {
            return "bytes " + start + "-" + endInclusive + "/" + totalLength;
        }
    }

    /** 请求的区间落在文件之外 —— 返回 416。 */
    record Unsatisfiable(long totalLength) implements RangeResolution {

        /** 416 响应必须带 {@code Content-Range: bytes} 星号斜杠长度。 */
        public String contentRangeHeader() {
            return "bytes */" + totalLength;
        }
    }
}
```

- [ ] **Step 4: 实现解析器**

`src/main/java/com/mymedia/video/range/RangeParser.java`：

```java
package com.mymedia.video.range;

/**
 * HTTP Range 头解析，遵循 RFC 9110 §14.1。
 *
 * <p>三条容易写错的规则：
 * <ul>
 *   <li>{@code bytes=-500} 是「<b>最后</b> 500 字节」，不是「从 0 到 500」</li>
 *   <li>Range 两端都是<b>闭区间</b>，长度是 {@code end - start + 1}</li>
 *   <li>语法错误或不认识的 unit 应当<b>忽略 Range 头返回完整内容</b>，
 *       而不是报错——只有语法正确但区间越界才返回 416</li>
 * </ul>
 *
 * <p>多重 Range 按 spec 7.3 的决策返回<b>并集</b>（最小起点到最大终点），
 * 不实现 {@code multipart/byteranges}。浏览器的 {@code <video>} 元素不发送多重 Range。
 */
public final class RangeParser {

    private static final String UNIT_PREFIX = "bytes";

    private RangeParser() {
    }

    public static RangeResolution resolve(String rangeHeader, long fileLength) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new RangeResolution.Full(fileLength);
        }

        String header = rangeHeader.replace(" ", "");
        int equals = header.indexOf('=');
        if (equals < 0) {
            return new RangeResolution.Full(fileLength);
        }

        String unit = header.substring(0, equals);
        if (!UNIT_PREFIX.equalsIgnoreCase(unit)) {
            // RFC 9110：无法识别的 unit 必须忽略整个 Range 头
            return new RangeResolution.Full(fileLength);
        }

        String spec = header.substring(equals + 1);
        if (spec.isEmpty()) {
            return new RangeResolution.Full(fileLength);
        }

        long unionStart = Long.MAX_VALUE;
        long unionEnd = Long.MIN_VALUE;
        boolean anyValid = false;
        boolean anyUnsatisfiable = false;

        for (String part : spec.split(",")) {
            long[] resolved = resolveSingle(part, fileLength);
            if (resolved == null) {
                // 语法错误：整个 Range 头作废，返回完整内容
                return new RangeResolution.Full(fileLength);
            }
            if (resolved.length == 0) {
                anyUnsatisfiable = true;
                continue;
            }
            anyValid = true;
            unionStart = Math.min(unionStart, resolved[0]);
            unionEnd = Math.max(unionEnd, resolved[1]);
        }

        if (!anyValid) {
            return anyUnsatisfiable
                    ? new RangeResolution.Unsatisfiable(fileLength)
                    : new RangeResolution.Full(fileLength);
        }
        return new RangeResolution.Partial(unionStart, unionEnd, fileLength);
    }

    /**
     * @return {@code null} 表示语法错误；长度为 0 的数组表示语法正确但不可满足；
     *         否则为 {@code [start, endInclusive]}
     */
    private static long[] resolveSingle(String part, long fileLength) {
        int dash = part.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = part.substring(0, dash);
        String endText = part.substring(dash + 1);

        if (startText.isEmpty() && endText.isEmpty()) {
            return null;
        }

        try {
            if (startText.isEmpty()) {
                // 后缀形式 bytes=-N：最后 N 个字节
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0 || fileLength == 0) {
                    return new long[0];
                }
                long start = Math.max(0, fileLength - suffixLength);
                return new long[]{start, fileLength - 1};
            }

            long start = Long.parseLong(startText);
            if (start < 0 || start >= fileLength) {
                return new long[0];
            }

            long end = endText.isEmpty() ? fileLength - 1 : Long.parseLong(endText);
            if (end < start) {
                return new long[0];
            }
            return new long[]{start, Math.min(end, fileLength - 1)};

        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=RangeParserTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/video/range src/test/java/com/mymedia/video/range
git commit -m "feat: 添加 HTTP Range 头解析器

三条易错规则已用测试锁定：
bytes=-500 是最后 500 字节；两端闭区间长度为 end-start+1；
语法错误应忽略 Range 返回完整内容，只有越界才返回 416。"
```

Expected: `EXIT=0`，`Tests run: 16, Failures: 0`

---

## Task 8: 流式传输端点

**Files:**
- Create: `src/main/java/com/mymedia/video/VideoStreamService.java`
- Create: `src/main/java/com/mymedia/video/web/VideoStreamController.java`
- Test: `src/test/java/com/mymedia/video/VideoStreamControllerTest.java`

**Interfaces:**
- Consumes: `RangeParser`、`RangeResolution`（Task 7）、`VideoCatalogService`（Task 5）、`ScannedFileQueryService`（计划 02）、`LibraryAccessService`（计划 01）
- Produces:
  - `public class VideoStreamService` — `public StreamTarget locate(Long userId, Long fileId)`，返回 `record StreamTarget(Path path, long sizeBytes, String etag, Instant lastModified, String contentType)`
  - `GET /api/video/stream/{fileId}` — 完整 Range 语义

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoStreamControllerTest.java`：

```java
package com.mymedia.video;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoStreamControllerTest extends AbstractIntegrationTest {

    private static final String CONTENT = "0123456789ABCDEFGHIJ";   // 20 字节

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
    VideoCatalogService catalogService;

    private String username;
    private Long fileId;

    private void setUpLibraryWithFile() throws IOException {
        Path file = root.resolve("电影/片子.mkv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CONTENT);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        fileId = catalogService.filesOf(item.getId()).getFirst().getId();
    }

    @Test
    void fullRequestReturns200WithAcceptRanges() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo(CONTENT);
    }

    @Test
    void rangeRequestReturns206WithContentRange() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/20"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("01234");
    }

    @Test
    void openEndedRangeReadsToEnd() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=15-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 15-19/20"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("FGHIJ");
    }

    @Test
    void suffixRangeReadsLastBytes() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 17-19/20"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("HIJ");
    }

    @Test
    void unsatisfiableRangeReturns416WithContentRange() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=999-1999"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */20"));
    }

    @Test
    void malformedRangeFallsBackToFullContent() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=abc-def"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()));
    }

    @Test
    void responseCarriesEtag() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId).with(httpBasic(username, "pw")))
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    @Test
    void ifRangeMismatchReturnsFullContent() throws Exception {
        setUpLibraryWithFile();

        // If-Range 的 ETag 对不上时，服务端必须忽略 Range 返回完整内容，
        // 否则客户端会把新旧内容的字节拼在一起，得到损坏的文件。
        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=0-4")
                        .header(HttpHeaders.IF_RANGE, "\"stale-etag\""))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()));
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        setUpLibraryWithFile();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/video/stream/" + fileId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoStreamControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 实现定位与鉴权服务**

`src/main/java/com/mymedia/video/VideoStreamService.java`：

```java
package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;

@Service
public class VideoStreamService {

    private final VideoCatalogService catalogService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final LibraryAccessService accessService;

    VideoStreamService(VideoCatalogService catalogService,
                       ScannedFileQueryService scannedFiles,
                       LibraryService libraryService,
                       LibraryAccessService accessService) {
        this.catalogService = catalogService;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.accessService = accessService;
    }

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

    private static String contentTypeOf(String extension) {
        return switch (extension) {
            case "mp4", "m4v" -> "video/mp4";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "ts", "m2ts" -> "video/mp2t";
            default -> "application/octet-stream";
        };
    }

    public record StreamTarget(
            Path path,
            long sizeBytes,
            String etag,
            Instant lastModified,
            String contentType) {
    }
}
```

- [ ] **Step 4: 实现流式端点**

`src/main/java/com/mymedia/video/web/VideoStreamController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoStreamService;
import com.mymedia.video.range.RangeParser;
import com.mymedia.video.range.RangeResolution;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
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
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardOpenOption;

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

        VideoStreamService.StreamTarget target = streamService.locate(userId, fileId);

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

- [ ] **Step 5: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoStreamControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/java/com/mymedia/video src/test/java/com/mymedia/video/VideoStreamControllerTest.java
git commit -m "feat: 添加视频 Range 流式传输端点

完整 206/Content-Range/Accept-Ranges/If-Range/416 语义。
If-Range 不匹配必须返回完整内容，否则客户端会拼出损坏的文件。
FileChannel.transferTo 零拷贝，配合虚拟线程承载阻塞 IO。"
```

Expected: `EXIT=0`，`Tests run: 10, Failures: 0`

---

## Task 9: 播放进度与继续观看

**Files:**
- Create: `src/main/resources/db/migration/V7__video_progress.sql`
- Create: `src/main/java/com/mymedia/video/VideoProgress.java`
- Create: `src/main/java/com/mymedia/video/VideoProgressRepository.java`
- Create: `src/main/java/com/mymedia/video/VideoProgressService.java`
- Create: `src/main/java/com/mymedia/video/web/VideoProgressController.java`
- Test: `src/test/java/com/mymedia/video/VideoProgressServiceTest.java`

**Interfaces:**
- Consumes: `VideoFile`（Task 3）、`UserAccount`（计划 01）
- Produces:
  - `public class VideoProgressService`
    - `public void record(Long userId, Long fileId, int positionSeconds, Integer durationSeconds)`
    - `public Optional<VideoProgress> find(Long userId, Long fileId)`
    - `public List<VideoProgress> continueWatching(Long userId, int limit)`
  - `PUT /api/video/progress/{fileId}`、`GET /api/video/continue-watching`

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/video/VideoProgressServiceTest.java`：

```java
package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoProgressServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    VideoProgressService progressService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    VideoCatalogService catalogService;

    private UserAccount newUser() {
        return registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
    }

    private List<Long> setUpFiles(String... names) throws IOException {
        for (String name : names) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent() == null ? root : file.getParent());
            Files.writeString(file, "c-" + name);
        }
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();

        return catalogService.findByLibrary(library.getId()).stream()
                .flatMap(item -> catalogService.filesOf(item.getId()).stream())
                .map(VideoFile::getId)
                .toList();
    }

    @Test
    void recordsProgressForUser() throws IOException {
        Long fileId = setUpFiles("电影/a.mkv").getFirst();
        UserAccount user = newUser();

        progressService.record(user.getId(), fileId, 120, 3600);

        var progress = progressService.find(user.getId(), fileId).orElseThrow();
        assertThat(progress.getPositionSeconds()).isEqualTo(120);
        assertThat(progress.getDurationSeconds()).isEqualTo(3600);
        assertThat(progress.isCompleted()).isFalse();
    }

    @Test
    void progressIsPerUser() throws IOException {
        Long fileId = setUpFiles("电影/b.mkv").getFirst();
        UserAccount alice = newUser();
        UserAccount bob = newUser();

        progressService.record(alice.getId(), fileId, 120, 3600);
        progressService.record(bob.getId(), fileId, 999, 3600);

        // 用户态数据独立成表，互不干扰 —— 这是多用户设计的核心
        assertThat(progressService.find(alice.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(120);
        assertThat(progressService.find(bob.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(999);
    }

    @Test
    void repeatedRecordUpdatesInPlace() throws IOException {
        Long fileId = setUpFiles("电影/c.mkv").getFirst();
        UserAccount user = newUser();

        progressService.record(user.getId(), fileId, 100, 3600);
        progressService.record(user.getId(), fileId, 200, 3600);
        progressService.record(user.getId(), fileId, 300, 3600);

        assertThat(progressService.find(user.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(300);
    }

    @Test
    void nearEndMarksCompleted() throws IOException {
        Long fileId = setUpFiles("电影/d.mkv").getFirst();
        UserAccount user = newUser();

        // 播到 96% 即视为看完 —— 片尾曲期间用户通常直接关掉
        progressService.record(user.getId(), fileId, 2880, 3000);

        assertThat(progressService.find(user.getId(), fileId).orElseThrow()
                .isCompleted()).isTrue();
    }

    @Test
    void continueWatchingExcludesCompletedAndOrdersByRecency() throws IOException {
        List<Long> fileIds = setUpFiles("电影/e1.mkv", "电影/e2.mkv", "电影/e3.mkv");
        UserAccount user = newUser();

        progressService.record(user.getId(), fileIds.get(0), 100, 3600);
        progressService.record(user.getId(), fileIds.get(1), 3550, 3600);   // 看完了
        progressService.record(user.getId(), fileIds.get(2), 200, 3600);

        List<VideoProgress> continueList = progressService.continueWatching(user.getId(), 10);

        assertThat(continueList).extracting(VideoProgress::getVideoFileId)
                .containsExactly(fileIds.get(2), fileIds.get(0));
    }

    @Test
    void continueWatchingRespectsLimit() throws IOException {
        List<Long> fileIds = setUpFiles("电影/f1.mkv", "电影/f2.mkv", "电影/f3.mkv");
        UserAccount user = newUser();
        for (Long id : fileIds) {
            progressService.record(user.getId(), id, 100, 3600);
        }

        assertThat(progressService.continueWatching(user.getId(), 2)).hasSize(2);
    }

    @Test
    void deletingUserCascadesProgress() throws IOException {
        Long fileId = setUpFiles("电影/g.mkv").getFirst();
        UserAccount user = newUser();
        progressService.record(user.getId(), fileId, 100, 3600);

        // 通过仓储直接删用户，验证外键级联
        assertThat(progressService.find(user.getId(), fileId)).isPresent();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoProgressServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -5
```

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V7__video_progress.sql`：

```sql
-- 用户态数据独立成表，绝不塞进媒体表。这是多用户设计的核心：
-- 同一部片子，每个用户有各自的进度，互不干扰。
CREATE TABLE video_progress (
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    video_file_id    BIGINT      NOT NULL REFERENCES video_file (id) ON DELETE CASCADE,
    position_seconds INT         NOT NULL,
    duration_seconds INT,
    completed        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_file_id)
);

-- 「继续观看」查询：按用户过滤、排除已看完、按时间倒序
CREATE INDEX idx_video_progress_continue
    ON video_progress (user_id, updated_at DESC)
    WHERE completed = FALSE;
```

- [ ] **Step 4: 写实体与仓储**

`src/main/java/com/mymedia/video/VideoProgress.java`：

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
@Table(name = "video_progress")
@IdClass(VideoProgress.Key.class)
public class VideoProgress {

    /** 播到多少比例即视为看完。片尾曲期间用户通常直接关掉。 */
    private static final double COMPLETION_RATIO = 0.95;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "video_file_id")
    private Long videoFileId;

    @Column(name = "position_seconds", nullable = false)
    private int positionSeconds;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VideoProgress() {
    }

    VideoProgress(Long userId, Long videoFileId) {
        this.userId = userId;
        this.videoFileId = videoFileId;
    }

    public Long getUserId() { return userId; }
    public Long getVideoFileId() { return videoFileId; }
    public int getPositionSeconds() { return positionSeconds; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public boolean isCompleted() { return completed; }
    public Instant getUpdatedAt() { return updatedAt; }

    void update(int positionSeconds, Integer durationSeconds) {
        this.positionSeconds = positionSeconds;
        if (durationSeconds != null) {
            this.durationSeconds = durationSeconds;
        }
        this.completed = this.durationSeconds != null
                && this.durationSeconds > 0
                && positionSeconds >= this.durationSeconds * COMPLETION_RATIO;
        this.updatedAt = Instant.now();
    }

    /** JPA 复合主键类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long videoFileId;

        public Key() {
        }

        public Key(Long userId, Long videoFileId) {
            this.userId = userId;
            this.videoFileId = videoFileId;
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
                    && Objects.equals(videoFileId, other.videoFileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, videoFileId);
        }
    }
}
```

`src/main/java/com/mymedia/video/VideoProgressRepository.java`：

```java
package com.mymedia.video;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface VideoProgressRepository extends JpaRepository<VideoProgress, VideoProgress.Key> {

    Optional<VideoProgress> findByUserIdAndVideoFileId(Long userId, Long videoFileId);

    @Query("""
            SELECT p FROM VideoProgress p
            WHERE p.userId = :userId AND p.completed = false
            ORDER BY p.updatedAt DESC
            """)
    List<VideoProgress> findContinueWatching(@Param("userId") Long userId, Pageable pageable);
}
```

- [ ] **Step 5: 写服务与端点**

`src/main/java/com/mymedia/video/VideoProgressService.java`：

```java
package com.mymedia.video;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class VideoProgressService {

    private final VideoProgressRepository repository;
    private final VideoCatalogService catalogService;

    VideoProgressService(VideoProgressRepository repository, VideoCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void record(Long userId, Long fileId, int positionSeconds, Integer durationSeconds) {
        catalogService.getFile(fileId);      // 不存在则抛 NotFoundException

        VideoProgress progress = repository.findByUserIdAndVideoFileId(userId, fileId)
                .orElseGet(() -> new VideoProgress(userId, fileId));
        progress.update(positionSeconds, durationSeconds);
        repository.save(progress);
    }

    @Transactional(readOnly = true)
    public Optional<VideoProgress> find(Long userId, Long fileId) {
        return repository.findByUserIdAndVideoFileId(userId, fileId);
    }

    @Transactional(readOnly = true)
    public List<VideoProgress> continueWatching(Long userId, int limit) {
        return repository.findContinueWatching(userId, PageRequest.of(0, limit));
    }
}
```

`src/main/java/com/mymedia/video/web/VideoProgressController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoProgress;
import com.mymedia.video.VideoProgressService;
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
@RequestMapping("/api/video")
class VideoProgressController {

    private final VideoProgressService progressService;
    private final UserQueryService userQueryService;

    VideoProgressController(VideoProgressService progressService,
                            UserQueryService userQueryService) {
        this.progressService = progressService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/progress/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@AuthenticationPrincipal UserDetails principal,
                @PathVariable Long fileId,
                @Valid @RequestBody ProgressRequest request) {
        progressService.record(currentUserId(principal), fileId,
                request.positionSeconds(), request.durationSeconds());
    }

    @GetMapping("/continue-watching")
    List<ProgressResponse> continueWatching(@AuthenticationPrincipal UserDetails principal,
                                            @RequestParam(defaultValue = "20") int limit) {
        return progressService.continueWatching(currentUserId(principal), limit).stream()
                .map(ProgressResponse::from)
                .toList();
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }

    record ProgressRequest(@Min(0) int positionSeconds, Integer durationSeconds) {
    }

    record ProgressResponse(Long fileId, int positionSeconds, Integer durationSeconds, boolean completed) {

        static ProgressResponse from(VideoProgress progress) {
            return new ProgressResponse(progress.getVideoFileId(), progress.getPositionSeconds(),
                    progress.getDurationSeconds(), progress.isCompleted());
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过并提交**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=VideoProgressServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
rm -f t.log
git add src/main/resources/db/migration/V7__video_progress.sql src/main/java/com/mymedia/video src/test/java/com/mymedia/video/VideoProgressServiceTest.java
git commit -m "feat: 添加播放进度与继续观看

用户态数据独立成表，同一部片子每个用户各自记进度。
播到 95% 即视为看完——片尾曲期间用户通常直接关掉。"
```

Expected: `EXIT=0`，`Tests run: 7, Failures: 0`

---

## Task 10: 条目 API、全量验证与讲解文档

**Files:**
- Create: `src/main/java/com/mymedia/video/web/VideoCatalogController.java`
- Create: `src/main/java/com/mymedia/video/web/VideoCatalogDto.java`
- Create: `docs/walkthrough/03-视频域.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `VideoCatalogService`（Task 5）、`LibraryAccessService`（计划 01）
- Produces: `GET /api/video/items`、`GET /api/video/items/{id}`、`GET /api/video/items/{id}/episodes`

- [ ] **Step 1: 写 DTO 与控制器**

`src/main/java/com/mymedia/video/web/VideoCatalogDto.java`：

```java
package com.mymedia.video.web;

import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoGroup;
import com.mymedia.video.VideoItem;

import java.util.List;

public final class VideoCatalogDto {

    private VideoCatalogDto() {
    }

    public record ItemSummary(Long id, String title, String itemType, String structure) {

        static ItemSummary from(VideoItem item) {
            return new ItemSummary(item.getId(), item.getTitle(),
                    item.getItemType().name(), item.getStructure().name());
        }
    }

    public record GroupSummary(Long id, int groupIndex, String name) {

        static GroupSummary from(VideoGroup group) {
            return new GroupSummary(group.getId(), group.getGroupIndex(), group.getName());
        }
    }

    public record FileSummary(Long id, String role, Integer episodeIndex,
                              Integer durationSeconds, Integer width, Integer height) {

        static FileSummary from(VideoFile file) {
            return new FileSummary(file.getId(), file.getRole().name(), file.getEpisodeIndex(),
                    file.getDurationSeconds(), file.getWidth(), file.getHeight());
        }
    }

    public record ItemDetail(ItemSummary item, List<GroupSummary> groups, List<FileSummary> files) {
    }
}
```

`src/main/java/com/mymedia/video/web/VideoCatalogController.java`：

```java
package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video/items")
class VideoCatalogController {

    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoCatalogController(VideoCatalogService catalogService,
                           LibraryAccessService accessService,
                           UserQueryService userQueryService) {
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<VideoCatalogDto.ItemSummary> list(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == com.mymedia.library.LibraryDomain.VIDEO)
                .map(MediaLibrary::getId)
                .flatMap(libraryId -> catalogService.findByLibrary(libraryId).stream())
                .map(VideoCatalogDto.ItemSummary::from)
                .toList();
    }

    @GetMapping("/{id}")
    VideoCatalogDto.ItemDetail detail(@AuthenticationPrincipal UserDetails principal,
                                      @PathVariable Long id) {
        VideoItem item = catalogService.getItem(id);
        requireAccess(principal, item);

        return new VideoCatalogDto.ItemDetail(
                VideoCatalogDto.ItemSummary.from(item),
                catalogService.groupsOf(id).stream()
                        .map(VideoCatalogDto.GroupSummary::from).toList(),
                catalogService.filesOf(id).stream()
                        .map(VideoCatalogDto.FileSummary::from).toList());
    }

    @GetMapping("/{id}/episodes")
    List<VideoCatalogDto.FileSummary> episodes(@AuthenticationPrincipal UserDetails principal,
                                               @PathVariable Long id) {
        VideoItem item = catalogService.getItem(id);
        requireAccess(principal, item);
        return catalogService.filesOf(id).stream()
                .map(VideoCatalogDto.FileSummary::from)
                .toList();
    }

    private void requireAccess(UserDetails principal, VideoItem item) {
        if (!accessService.canAccess(currentUserId(principal), item.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到视频条目 id=" + item.getId());
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
```

- [ ] **Step 2: 运行全部测试**

```bash
cd /d/MyMedia && mvn -B -ntp verify > full.log 2>&1; echo "EXIT=$?"; grep -E "Tests run:|BUILD" full.log | tail -6
```

Expected: `EXIT=0`，`BUILD SUCCESS`，全部通过。**不通过不要继续。**

- [ ] **Step 3: 手工端到端验证**

```bash
cd /d/MyMedia && docker compose up -d
mkdir -p /tmp/mm/番剧/进击的巨人 /tmp/mm/电影
printf 'A%.0s' $(seq 1 100000) > "/tmp/mm/电影/黑客帝国 (1999).mkv"
printf 'B%.0s' $(seq 1 100000) > /tmp/mm/番剧/进击的巨人/S01E01.mkv
printf 'C%.0s' $(seq 1 100000) > /tmp/mm/番剧/进击的巨人/S01E02.mkv

mvn -B -ntp spring-boot:run > run.log 2>&1 &
sleep 30

LIB=$(curl -s -u admin:admin -X POST http://localhost:8080/api/libraries \
  -H 'Content-Type: application/json' \
  -d '{"name":"视频","domain":"VIDEO","rootPath":"/tmp/mm"}' | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -u admin:admin -X POST "http://localhost:8080/api/libraries/$LIB/scan"
sleep 10

echo "=== 条目列表 ==="
curl -s -u admin:admin http://localhost:8080/api/video/items
echo; echo "=== 目录树浏览 ==="
curl -s -u admin:admin "http://localhost:8080/api/video/browse?libraryId=$LIB"
```

Expected: 两个条目（`黑客帝国` 为 MOVIE/FLAT，`进击的巨人` 为 SERIES/GROUPED）；目录树返回 `电影` 与 `番剧` 两个顶层目录。

- [ ] **Step 4: 验证 Range 语义**

```bash
FID=$(curl -s -u admin:admin http://localhost:8080/api/video/items | python -c "
import sys,json
items=json.load(sys.stdin)
print(items[0]['id'])")
DETAIL=$(curl -s -u admin:admin "http://localhost:8080/api/video/items/$FID")
FILE=$(echo "$DETAIL" | python -c "import sys,json;print(json.load(sys.stdin)['files'][0]['id'])")

echo "=== 完整请求 ==="
curl -s -o /dev/null -D - -u admin:admin "http://localhost:8080/api/video/stream/$FILE" | head -8
echo "=== Range 0-9 ==="
curl -s -o /dev/null -D - -u admin:admin -H 'Range: bytes=0-9' "http://localhost:8080/api/video/stream/$FILE" | head -8
echo "=== 越界 Range ==="
curl -s -o /dev/null -D - -u admin:admin -H 'Range: bytes=999999-' "http://localhost:8080/api/video/stream/$FILE" | head -6
```

Expected：
- 完整请求 → `200`，`Accept-Ranges: bytes`，`ETag` 存在
- `Range: bytes=0-9` → `206`，`Content-Range: bytes 0-9/100000`，`Content-Length: 10`
- 越界 → `416`，`Content-Range: bytes */100000`

- [ ] **Step 5: 写讲解文档**

`docs/walkthrough/03-视频域.md`，必须覆盖以下八个问题：

1. **HTTP Range 请求的完整语义。** 逐条解释 `206` / `Content-Range` / `Accept-Ranges` / `416`；`bytes=-500` 为什么是"最后 500 字节"；为什么长度是 `end - start + 1`。
2. **`If-Range` 解决什么问题？** 描述客户端拿着旧 ETag 续传、服务端若不校验会拼出损坏文件的具体场景。
3. **为什么语法错误的 Range 返回 200 而不是 400？** RFC 9110 的规定与理由。
4. **`FileChannel.transferTo` 为什么叫零拷贝？** 对比普通的 `read` 到堆再 `write` 出去，说明省掉了哪两次拷贝。
5. **`structure` 为什么不由 `item_type` 推导？** 举一个"电影但是 GROUPED"的真实例子。
6. **文件名解析为什么永不抛异常？** 联系 spec §7.2 规则 1：没有刮削也必须完全可用。
7. **物化路径 vs 递归查询。** 面包屑为什么能一次查询搞定；子树移动的代价是什么。
8. **自然排序为什么不能把数字解析成 long？** 20 位数溢出的具体例子。

- [ ] **Step 6: 更新 README 并提交**

在 README 功能清单追加：视频条目浏览、目录树视图、Range 流式播放、播放进度、继续观看。

```bash
cd /d/MyMedia
rm -f full.log run.log t.log
rm -rf /tmp/mm
git add -A
git commit -m "feat: 完成视频域阶段

语义模型、文件名解析、目录树浏览、Range 流式播放、播放进度。
附讲解文档。"
```

---

## Self-Review

**1. Spec 覆盖检查**

| spec 章节 | 覆盖任务 |
|---|---|
| §5.3 视频域端点（items / episodes / stream / progress / continue-watching / browse） | Task 6、8、9、10 |
| §6.3 `video_item`（含 structure 独立字段） | Task 3 |
| §6.3 `video_group` | Task 3、5 |
| §6.3 `video_file`（含 role 枚举） | Task 3 |
| §6.3 `collection` / `collection_item` | Task 3（建表）；**合集的填充逻辑延后至计划 05**，需刮削结果才能识别系列归属 |
| §6.3 `video_folder` 目录树 | Task 3、6 |
| §6.5 `video_progress` | Task 9 |
| §7.1 自然排序（延自计划 02） | Task 1 |
| §7.1 子树移动前缀替换（延自计划 02） | Task 2（`MaterializedPath.rewrite`，视频域暂无移动场景，计划 04 使用） |
| §7.3 Range 全套语义 | Task 7、8 |
| §7.3 零拷贝 + 虚拟线程 | Task 8 |
| 路线图 P4 / P5 | Task 3–6 / Task 7–9 |

**明确延后**：`collection` 只建表不填充（需刮削）；`video_file` 的 `duration/width/height/codec` 字段由计划 05 的 ffprobe 回填，本计划留出 `applyProbe` 方法。

**2. 占位符扫描**：已通过。唯一的"占位实现"是 Task 5 Step 7 的 `VideoFolderIndexer` 空实现，**已明确标注由 Task 6 替换并给出完整代码**，不属于计划占位符。

**3. 类型一致性检查**

| 标识符 | 定义于 | 被引用于 | 一致 |
|---|---|---|---|
| `NaturalSortKey.of(String)` | Task 1 | Task 3（三个实体） | ✓ |
| `MaterializedPath.rootPath/childOf/ancestorIds/depthOf/subtreePrefix/rewrite` | Task 2 | Task 3（`VideoFolder`）、Task 6 | ✓ |
| `VideoItemType` / `VideoStructure` / `VideoFileRole` | Task 3 | Task 5、10 | ✓ |
| `VideoItem` + `assignFolder/promoteToGrouped/rename` | Task 3 | Task 5、6 | ✓ |
| `VideoFolder` + `finalizePath/setCounts` | Task 3 | Task 6 | ✓ |
| `VideoFile` + `assignGroup/applyProbe` | Task 3 | Task 5、计划 05 | ✓ |
| `ParsedVideoName(title, season, episode, year, quality)` | Task 4 | Task 5 | ✓ |
| `VideoFilenameParser.parse(String)` | Task 4 | Task 5 | ✓ |
| `VideoCatalogService.getItem/findByLibrary/groupsOf/filesOf/episodesOf/getFile` | Task 5 | Task 8、9、10、各测试 | ✓ |
| `VideoFolderIndexer.attachItemToFolder` | Task 5（占位）/ Task 6（实现） | Task 5 | ✓ |
| `VideoBrowseService.childFolders/itemsIn/getFolder/breadcrumb` | Task 6 | Task 6 控制器、测试 | ✓ |
| `RangeResolution.Full/Partial/Unsatisfiable` + `contentLength/contentRangeHeader` | Task 7 | Task 8 | ✓ |
| `RangeParser.resolve(String, long)` | Task 7 | Task 8 | ✓ |
| `VideoStreamService.locate` + `StreamTarget(path, sizeBytes, etag, lastModified, contentType)` | Task 8 | Task 8 控制器 | ✓ |
| `VideoProgressService.record/find/continueWatching` | Task 9 | Task 9 控制器、测试 | ✓ |
| `VideoProgress` + `getVideoFileId/getPositionSeconds/isCompleted` | Task 9 | Task 9 | ✓ |
| `JobPoller.pollOnce()`（计划 02 引入） | 计划 02 Task 7 | Task 5、6、8、9 的测试 | ✓ |
| `LibraryContentBuilder`（计划 02 定义） | 计划 02 Task 7 | Task 5 实现 | ✓ |

**编写中发现并已处理的一处**：Task 5 的 `VideoContentBuilder` 依赖 `VideoFolderIndexer`，而后者属于 Task 6。已在 Task 5 Step 7 给出临时空实现并明确标注 Task 6 替换，使两个任务都能独立通过各自的测试。
