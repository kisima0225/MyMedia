# MyMedia 规划状态

> 更新时间：2026-08-18
> 用途：跨会话交接。**新窗口先读本文件**，再读 `docs/superpowers/plans/2026-08-17-00-总览与交接.md`，
> 最后读要执行或续写的那份计划。

---

## 1. 一句话现状

计划 01 的**代码**已执行完毕并合入 `main`；**计划 02–06 的计划文本已全部写完**；
下一步二选一：**执行计划 02**（扫描框架，依赖已就位），或**开始写计划 07**（前端）。

---

## 2. 已确认的结论

### 2.1 计划 06 的四个拍板决策

| 决策点 | 结论 |
|---|---|
| 搜索与标签放哪个模块 | **不新开自造模块**。各领域模块提供自己的搜索与收藏；标签归 `metadata`（它已有 video/image 两条边）；全局搜索放进 spec §4.2 早已声明、计划 06 才创建的 `web` 模块 |
| 秒传如何处理 `content_hash` 大面积为空 | **命中即秒传 + 同尺寸候选现算**：先按 hash 查；未命中时取同库内 `size_bytes` 相同且 hash 为空的候选（上限 8 个）现算并写回再比 |
| 分享链接的免登录访问 | **独立 `/api/share/{token}/**` 前缀**，`permitAll`；令牌解析成临时只读授权（`ShareGrant`），复用既有的 `VideoStreamService` / `ImagePageService`，不复制播放与阅读逻辑 |
| 计划 06 是否拆分 | **不拆**，P10（Task 1–9）在前 P11（Task 10–13）在后，两段之间是一个可独立验收的分界点 |

另有一条我给出建议、用户未反对、已按此写进计划的：
**接受「两字中文查询走全表扫描」这个上界**（10 万条目 29ms），理由与退路写进 ADR-006。

### 2.2 实测验证的事实（不要凭记忆推翻）

已全部并入 `2026-08-17-00-总览与交接.md` 的 §3 事实表与计划 06 的 Global Constraints，
这里只留索引：

- **pg_trgm 的中文边界**：`similarity('进击的巨人','巨人') = 0.125`（阈值 0.3）→ `%` 操作符
  不能当中文子串谓词；两字查询用不上 GIN trgm 索引（10 万行 29ms，与顺序扫描持平）。
- **tsvector 对拉丁文有真实价值**：查 `movies` 命中 `Sintel the Movie`，`ILIKE` 命中 0 行。
- **生成列必须用双参数 `to_tsvector(regconfig, text)`**，单参数是 STABLE 的会被拒绝。
- **Boot 4 的 multipart 属性没改名**（`spring.servlet.multipart.*`，传递引入，`compile`
  作用域，无需新增依赖），但默认上限 1MB/10MB → 计划 06 的分片体走原始
  `application/octet-stream`，绕开 multipart 解析器。
- **Jackson 3**（写计划 05 时验证）：`tools.jackson.databind`，`JacksonException` 不受检，
  `asText()` → `asString()`，`fields()` → `properties()`；注解仍在 `com.fasterxml.jackson.annotation`。

> ⚠ 写计划 06 的过程中我一度根据 `target/*.jar` 的内容说 `spring-boot-servlet` 不在
> classpath 上——**那是错的**，当时 `target/` 已被外部清掉，读的是空气。
> `mvn dependency:tree` 才是权威结论。

### 2.3 已核实并已安排落点的跨计划缺口

| # | 缺口 | 归谁补 | 状态 |
|---|---|---|---|
| B1 | `search_vector` 列根本不存在（计划 03 的 V6、计划 04 的 V8 都漏了） | 计划 06 Task 1（V12） | ✅ 已写 |
| B2 | `tag`、`video_item_tag`、`image_node_tag`、`video_favorite`、`image_favorite`、`share_link`、`upload_session`、`upload_chunk` 八张表全未建 | 计划 06 Task 5/7/8/10（V13–V16） | ✅ 已写 |
| B3 | `SampledHash` 是 `com.mymedia.scan` 的 package-private，而秒传与合并后校验都要用 | 计划 06 Task 10：提升到 `com.mymedia.shared` | ✅ 已写 |
| B4 | `scanned_file.content_hash` 绝大多数为 NULL（计划 02 只在改名检测时才算） | 计划 06 用「同尺寸候选现算」兜住，边界写进 ADR-007 | ✅ 已写 |
| B5 | `SecurityConfig` 目前 `anyRequest().authenticated()`，分享链接免登录必须放行 | 计划 06 Task 9 | ✅ 已写 |
| B6 | 计划 02–04 缺 `@NamedInterface`（嵌套包跨模块引用会让 `verify()` 失败） | 补丁写在计划 05 Task 6 Step 1，执行计划 02 时取用 | ⬜ 待执行 |
| B7 | `VideoItem` 没有 `coverAssetId` 映射（列在 V6 里就有） | 计划 06 Task 6 Step 5（补成**只读**映射） | ✅ 已写 |
| B8 | `ImageNode.coverAssetId` 是可写映射，而计划 05 用 `JdbcTemplate` 写它——陈旧实体会把封面刷成 null | 建议执行计划 04/05 时改成只读映射，见总览 §5 | ⬜ 待执行 |

---

## 3. 已完成内容

### 3.1 代码（已在 `main` 上）

计划 01 全部执行完毕（PR #1，`c7267ee`）：`shared` / `user` / `library` / `jobs` 四个模块，
迁移 V1–V4（含补丁 `V1_1__event_publication.sql`）。

### 3.2 文档（**均未提交，在工作区里**）

| 文件 | 状态 |
|---|---|
| `docs/superpowers/plans/2026-08-17-05-preview-metadata.md` | ✅ 写完，12 个任务、约 9490 行 |
| `docs/superpowers/plans/2026-08-17-06-search-and-upload.md` | ✅ **写完**，13 个任务、97 个步骤、约 9420 行，含 Self-Review |
| `docs/superpowers/plans/2026-08-17-00-总览与交接.md` | ✅ 已更新（06 标记完成、迁移与任务类型编号、五条新实测事实、计划 06 带出的六件事、ADR 总表） |
| `docs/superpowers/plans/STATUS.md` | ✅ 本文件 |

会话记忆（仓库外）也已更新：
`~/.claude/projects/D--MyMedia/memory/mymedia-plan-split.md` 与 `MEMORY.md`。

### 3.3 计划 06 的十三个任务

| Task | 内容 | 迁移 |
|---|---|---|
| 1 | 搜索列、索引与查询规范化（`SearchQuery` 纯逻辑 + LIKE 转义） | `V12__search_columns.sql` |
| 2 | 视频域双路径搜索（分层排序：子串 → 相似度 → ts_rank → sort_title） | — |
| 3 | 图片域双路径搜索（搜 `name` 与 `title`；只搜 ACTIVE 节点） | — |
| 4 | `web` 模块与全局搜索端点 `GET /api/search`（分区返回不混排） | — |
| 5 | 标签模型与管理（复用 ADR-001 的复合外键手法做域分区强制） | `V13__tags.sql` |
| 6 | 打标签与按标签浏览（`setTags` 整体替换 + `findByIds` 保序 + `VideoItem` 只读封面映射） | — |
| 7 | 两个域的收藏（两份代码逐字写全，不用「照抄改名」） | `V14__favorites.sql` |
| 8 | 分享链接的创建、撤销与查询（服务住 `library`，创建端点被迫分到两个领域模块） | `V15__share_link.sql` |
| 9 | 分享链接的免登录访问（`permitAll` + HMAC 票据 + `locateForShare` + `VideoRangeResponder` 抽取） | — |
| 10 | `SampledHash` 提升到 `shared` + 上传会话与秒传（含 `SafeFileName` 安全边界） | `V16__upload.sql` |
| 11 | 分片上传与断点续传（`application/octet-stream` 流式落盘，不走 multipart） | — |
| 12 | 合并、校验、入库与触发扫描（`UPLOAD_ASSEMBLE` + 原子状态跃迁） | — |
| 13 | 全量验证、ADR-006/007 与讲解文档 | — |

---

## 4. 未决问题

1. **本次会话仍然没有跑过任何测试**——计划 02–06 都还只是文本，没有代码可跑。
2. **`target/` 目录被外部清掉了**（不是我删的）。不影响仓库内容，但要跑测试得先 `mvn` 重建。
3. **计划 07（前端）与 08（交付）未写。** 07 要调 `frontend-design` 技能，
   且需要先决定前端技术栈——这是一个需要用户拍板的决策点，不要自己定。
4. **计划 06 里有三处依赖上游计划的细节**，执行时若上游落地形态不同要跟着调：
   `derived_asset.source_scanned_file_id` 是否可空（Task 6 的最后一个用例）、
   `image_node` 建树时物化路径的回填方式（Task 9 的 `insertNode` 助手）、
   `MyMediaApplication` 上的 `@ConfigurationPropertiesScan`（计划 05 Task 1 Step 8 已加）。
   三处都在计划里写了「若不同该怎么调整」。

---

## 5. 下一步操作

### 5.1 执行代码（推荐）

**下一个可执行的是计划 02（扫描框架）**，它依赖的一切都已就位。
执行时第一件事是处理 §2.3 的 B6：给 `scan.spi` / `scan.event` 补 `@NamedInterface`
（补丁代码在计划 05 Task 6 Step 1），否则 `ApplicationModules.verify()` 过不了。

### 5.2 或者写计划 07（前端）

先用 `superpowers:brainstorming` 把技术栈与页面结构问清楚，再调 `frontend-design`。
计划 06 已经把前端需要的后端接口全部定死了（搜索、标签、收藏、分享、上传），
可以直接照着写调用。

### 5.3 提交建议

工作区里有四份文档改动未提交。建议**分两次提交**：
计划 05 单独一次，计划 06 + 总览 + STATUS 一次。

---

## 6. 相关文件

### 6.1 必读

| 文件 | 作用 |
|---|---|
| `docs/superpowers/plans/STATUS.md` | 本文件，跨会话第一入口 |
| `docs/superpowers/plans/2026-08-17-00-总览与交接.md` | 跨计划约定（迁移编号、模块依赖方向、JSONB/TEXT[] 不做 JPA 映射）、已实测事实表、已知遗留、ADR 总表 |
| `docs/superpowers/specs/2026-08-17-mymedia-design.md` | 设计文档，所有计划的依据 |

### 6.2 计划

| 文件 | 状态 |
|---|---|
| `2026-08-17-01-infrastructure.md` | 已写（13 任务）、**已执行** |
| `2026-08-17-02-scanning.md` | 已写（8 任务），**待执行** |
| `2026-08-17-03-video-domain.md` | 已写（10 任务） |
| `2026-08-17-04-image-domain.md` | 已写（10 任务） |
| `2026-08-17-05-preview-metadata.md` | 已写（12 任务） |
| `2026-08-17-06-search-and-upload.md` | 已写（13 任务） |
| 计划 07（前端）、08（交付） | 未写 |

### 6.3 决策记录

已有：`docs/adr/ADR-001-域分区的数据库级强制.md`、`ADR-002-认证方案.md`、
`ADR-003-用数据库任务表替代消息队列.md`

计划产出：ADR-004（刮削链不做 SPI 倒置）、ADR-005（刮削是可选增强）由计划 05 Task 12 产出；
ADR-006（中文搜索的真实边界）、ADR-007（采样哈希与秒传的边界）由计划 06 Task 13 产出。

### 6.4 编号占用

- **迁移**：V1–V4（01，含 `V1_1`）、V5（02）、V6–V7（03）、V8–V9（04）、V10–V11（05）、
  V12–V16（06）。**V17 起留给 07/08。** 规矩：一支迁移只由一个任务创建。
- **任务类型**：`LIBRARY_SCAN`(02)、`ARCHIVE_INDEX`(04)、`PREVIEW_GENERATE` /
  `SPRITE_GENERATE` / `METADATA_FETCH`(05)、`UPLOAD_ASSEMBLE`(06)、`TRANSCODE`(预留未用)。
- **ADR**：001–003 已存在，004–005 归计划 05，006–007 归计划 06。**008 起可用。**
