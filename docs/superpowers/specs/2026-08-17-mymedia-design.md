# MyMedia 设计文档

> 日期：2026-08-17
> 状态：已确认，待转实施计划

## 1. 项目定位

自托管的个人/小圈子媒体库服务端。单实例部署，多用户登录，各自拥有独立的观看进度、阅读进度与收藏；管理员配置媒体库。产品原型是 Jellyfin / Komga / Perfect Viewer 这一类，**不是 SaaS 多租户**。

本项目的首要用途是作为**后端方向的简历项目**。这一点决定了两条贯穿全文的原则：

1. **可解释性优先于技术新潮度。** 每一处选型都必须能在面试中说清"为什么这样、为什么不那样"。凡是说不出理由的技术，一律不引入。
2. **代码由执行 agent 产出，项目所有者通过阅读与使用来掌握它。** 因此每个阶段除了代码，还必须产出面向所有者的讲解文档。文档是交付物，不是附赠品。

## 2. 目标与非目标

### 目标

- 视频媒体：电影、番剧、单视频、系列视频
- 图片媒体：漫画、图集、动图集
- 扫描本地目录入库为主，Web 分片上传为辅
- 多用户 + 媒体库级访问控制 + 免登录分享链接
- 元数据刮削作为**可选增强**，不是必经步骤
- `docker compose up` 一键启动，自带版权安全的演示数据
- 高质量 README（架构图、截图、设计决策记录）

### 非目标

以下内容明确不做，理由记录在对应 ADR 中：

| 不做 | 理由 |
|---|---|
| 视频转码 / HLS | 无底洞，收益不及成本。留出 `TRANSCODE` job type 的位置，作为可选的最后阶段 |
| Redis | 单实例、无跨节点缓存需求。引入无法解释的中间件在面试中是负分 |
| 消息队列（RabbitMQ / Kafka） | 任务量小、单实例、需要可查询的任务历史。PostgreSQL 任务表更合适 |
| Elasticsearch | PostgreSQL 的 `pg_trgm` 三元组索引已能覆盖本项目的中文搜索需求与数据量，见 §7.7 |
| 微服务 | 严重过度设计。"为什么拆"这个问题现阶段答不了 |
| 弹幕 / 评论 / 社交 | 与媒体库定位无关 |
| 移动端 App | 响应式 Web 已覆盖 |
| 公网部署 / CI 自动部署 | 项目所有者没有服务器，交付形态是本地 Docker Compose + README |

## 3. 技术选型

### 3.1 版本矩阵（已实测验证，非凭记忆）

| 组件 | 版本 | 验证方式 |
|---|---|---|
| Spring Boot | `4.1.0.RELEASE` | Spring Initializr metadata 当前默认版本 |
| Java | `25`（本机已装 JDK 25.0.3 LTS） | Initializr 支持 17/21/25/26 |
| Spring Modulith | `2.1.0` | 由 Boot 4.1.0 的 BOM 管理；Initializr 声明兼容区间 `[4.0.0.RELEASE, 4.2.0.M1)` |
| PostgreSQL | 17（Docker 镜像） | — |
| Maven | 3.9.9（IDEA 自带） | 本机已装 |
| ffmpeg / ffprobe | 烘焙进应用 Docker 镜像 | 本机未装，不要求所有者安装 |

**Spring Boot 3.x 已退出 Initializr 的可选版本列表**（当前仅提供 4.0.x 与 4.1.x），新项目没有退路。

### 3.2 Boot 4.x 的 starter 改名 —— 执行 agent 必读

Boot 4 大幅调整了 starter 命名。绝大多数教程与训练数据仍是 3.x，**照记忆写会全部失败**。以下为实测生成的对照表：

| 3.x 写法（错误） | 4.1 正确写法 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `flyway-core` | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` |
| `spring-boot-starter-test` | 拆分为 `spring-boot-starter-webmvc-test`、`-data-jpa-test`、`-security-test`、`-validation-test`、`-actuator-test`、`-flyway-test` |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |

**实施计划中必须内联一份从 Spring Initializr 实测生成的完整 `pom.xml`，令执行 agent 照抄而非照记忆生成。**

### 3.3 其他关键选型

- **虚拟线程**：`spring.threads.virtual.enabled=true`。本项目是重阻塞 I/O 场景（大文件读写、ffmpeg 子进程、外部 HTTP 刮削），虚拟线程收益直接且易于解释。
- **Flyway**：所有 schema 变更走版本化迁移脚本，不用 `ddl-auto`。
- **Spring Security**：会话或 JWT 二选一，在实施计划中定稿；不引入 OAuth2 授权服务器。
- **Lombok**：使用，减少样板代码对阅读的干扰。

## 4. 架构：模块化单体

### 4.1 为什么是模块化单体

评估过三种形态：普通分层单体、模块化单体、微服务。选模块化单体的理由：

- 相比普通分层单体，只多一个依赖和一个测试类，却把"模块边界"从口头约定变成**有测试强制的约束**——越界调用会让 `ApplicationModules.verify()` 直接失败。
- 相比微服务，避免了对当前体量而言灾难性的过度设计。
- 附带产出：Spring Modulith 能自动生成模块结构图，直接用于 README。

面试叙事："我做的是模块化单体，模块边界有测试保障。将来若需拆分微服务，边界是现成的。"——这个回答的质量高于"我用了微服务"。

### 4.2 模块清单与依赖规则

```
com.mymedia
│
├── shared      基础类型、领域异常、分页、树路径算法（MaterializedPathSupport）
├── user        账号、角色、认证、Spring Security 配置
├── library     媒体库定义（domain 判别）、访问控制、分享链接
├── scan        物理文件遍历、指纹、对账、改名检测、SPI 定义
├── metadata    MetadataProvider 接口 + 各 Provider 实现 + 待确认队列
├── preview     封面、缩略图、进度条雪碧图生成（ffmpeg / 图片处理）
├── jobs        任务表、SKIP LOCKED 调度器、租约、重试退避
├── upload      分片上传、断点续传、秒传
│
├── video   ★ 视频域：语义模型、文件名规则、Range 流式播放、播放进度、目录树视图
├── image   ★ 图片域：任意深度节点树、CBZ 流式读取、分页阅读、阅读进度
│
└── web         静态资源托管、全局异常处理、OpenAPI 文档
```

**依赖规则（由架构测试强制）：**

1. 每个模块只暴露顶层包中的公开类型，实现类一律 package-private。
2. `scan` **不得依赖** `video` 或 `image`。它只定义 SPI（如 `MediaTypeResolver`、`LibraryContentBuilder`），由两个领域模块实现并注册。**加入第三个域（例如音频）不需要修改扫描代码。**
3. `video` 与 `image` **互不依赖**。
4. 跨模块的异步协作一律走领域事件，不走直接方法调用。

### 4.3 领域事件

`spring-modulith-starter-jpa` 提供事件发布注册表：事件在事务提交时持久化，消费失败或应用崩溃后可补发，避免"扫描完了但缩略图没生成"这类静默丢失。

主要事件：

| 事件 | 发布者 | 消费者 |
|---|---|---|
| `ScannedFileDiscovered` | `scan` | `video` / `image`（按 library.domain 分派） |
| `ScannedFileRelocated` | `scan` | 无需消费（语义层通过外键自动跟随） |
| `VideoItemCreated` | `video` | `metadata`、`preview` |
| `ImageNodeCreated` | `image` | `metadata`、`preview` |
| `LibraryScanCompleted` | `scan` | `web`（通知）、`preview`（批量补齐） |

## 5. 领域分区：视频域 / 图片域

两个域是本系统的第一层划分，分区在四个层面同时落实，缺一层都算假分区。

### 5.1 数据层

`libraries.domain` 必填、创建后不可变，取值 `VIDEO` | `IMAGE`。

分区必须由数据库**声明式强制**，不能只靠应用层自觉。但 CHECK 约束无法跨表引用，因此采用**复合外键**技术：

```sql
-- libraries 上加一个冗余唯一键，使 domain 可被外键引用
ALTER TABLE libraries ADD CONSTRAINT uq_library_domain UNIQUE (id, domain);

-- media_item 冗余 domain 列，并用复合外键把它钉死在所属库的 domain 上
ALTER TABLE media_item ADD CONSTRAINT fk_item_library_domain
  FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain);
ALTER TABLE media_item ADD CONSTRAINT ck_item_is_video
  CHECK (domain = 'VIDEO');

-- image_node 同理
ALTER TABLE image_node ADD CONSTRAINT fk_node_library_domain
  FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain);
ALTER TABLE image_node ADD CONSTRAINT ck_node_is_image
  CHECK (domain = 'IMAGE');
```

效果：**视频条目在数据库层面就不可能落进图片库**，反之亦然。`collection` 用同样手法约束。

条目类型与域的对应关系：

```
domain = VIDEO  →  media_item.item_type ∈ {MOVIE, SERIES, SINGLE_VIDEO, VIDEO_SERIES}
domain = IMAGE  →  image_node 节点树，无 item_type（见 §6.4）
```

### 5.2 模块层

见 §4.2。共享的只有账号、权限、扫描框架、任务队列、上传、预览生成这些**与内容形态无关**的基础设施。

### 5.3 接口层

两套端点，而非一套端点加 `type` 参数：

| 视频域 | 图片域 |
|---|---|
| `GET /api/video/items` | `GET /api/image/nodes` |
| `GET /api/video/items/{id}/episodes` | `GET /api/image/nodes/{id}/pages` |
| `GET /api/video/stream/{fileId}` | `GET /api/image/page/{fileId}` |
| `PUT /api/video/progress/{fileId}`（秒） | `PUT /api/image/progress/{nodeId}`（页码） |
| `GET /api/video/continue-watching` | `GET /api/image/continue-reading` |
| `GET /api/video/browse?folderId=` | `GET /api/image/browse?nodeId=` |

**决策**：拒绝 `/api/media?type=video` 式的统一接口。两个域的交互模型几乎零重叠，强行统一会导致接口中大量字段常年为 null。按领域切分而非按技术层切分。

### 5.4 体验层

登录后顶层即「视频」与「图片」两个入口，各有独立的首页、浏览、搜索与视觉语言。视频区为 16:9 横向卡片、播放进度条、继续观看轮播；图片区为书籍比例纵向卡片、瀑布流网格、阅读进度。**唯一交汇点是全局搜索，且结果分区展示，不混排。**

## 6. 数据模型

### 6.1 分层原则：物理层共享，语义层分域

这是整个数据模型的核心设计。

```
scanned_file  ← 物理层，domain 无关，由 scan 模块拥有
     ↑                    ↑
video_file            image_file     ← 语义层，各域拥有
```

`scan` 只认识"磁盘上有一个文件，它的路径、大小、修改时间、内容哈希是什么"，完全不关心它是电影还是漫画页。对账、改名检测、状态管理全部在物理层完成。

**这个分层带来一个关键收益：文件改名或移动时，只需更新 `scanned_file.relative_path` 一个字段，语义层通过外键自动跟随，用户的观看进度、收藏、手工编辑过的元数据全部无损保留。**

### 6.2 共享表

**`users`**
`id, username(uniq), password_hash, display_name, role(ADMIN|USER), enabled, created_at`

**`libraries`**
`id, name, domain(VIDEO|IMAGE, 不可变), root_path, scan_cron, metadata_providers(text[]), enabled, created_at`
`metadata_providers` 为空数组表示该库不刮削。

**`library_access`**
`user_id, library_id`（复合主键）。ADMIN 隐式拥有全部访问权。

**`share_link`**
```
token(uniq), library_id
video_item_id (nullable, → media_item)
image_node_id (nullable, → image_node)
password_hash(nullable), expires_at, created_by, created_at, revoked_at
CHECK (num_nonnulls(video_item_id, image_node_id) = 1)
```
用**两个可空外键 + CHECK 恰有一个非空**，而非 `(target_type, target_id)` 多态列。多态外键在 PostgreSQL 中无法建立引用完整性约束，删除目标条目会留下悬空记录。

**`derived_asset`** —— 派生资源，由 `preview` 模块拥有
```
id
kind                     COVER | THUMBNAIL | SPRITE_SHEET | SPRITE_VTT
source_scanned_file_id   → scanned_file，该资源从哪个原始文件生成
relative_path            相对于派生资源根目录（独立于媒体库路径，可随时清空重建）
width, height, size_bytes, generated_at
```
封面、缩略图、雪碧图都是**生成物**而非扫描所得，必须与 `scanned_file` 分开存放。所有派生资源统一从某个原始文件生成——视频封面来自抽帧、漫画封面来自首页、目录里现成的 `cover.jpg` 也会被归一化尺寸与格式后落为派生资源——因此 `source_scanned_file_id` 是**单一外键，无多态**。

派生资源目录独立于媒体库根路径，删光后可由任务队列全量重建，不影响任何用户数据。

**`scanned_file`**（物理层）
```
id, library_id
relative_path            (library_id, relative_path) 唯一
size_bytes, mtime
content_hash             采样哈希，见 §7.1
extension, mime_type
status                   ACTIVE | MISSING
first_seen_at, last_seen_at
```

**`job`**（任务表）
```
id, type, payload jsonb, priority
status                   PENDING | RUNNING | SUCCEEDED | FAILED | CANCELLED
attempts, max_attempts, last_error
dedup_key                唯一，防重复入队
scheduled_at, started_at, finished_at
lease_owner, lease_expires_at
```
`type` 取值：`LIBRARY_SCAN`、`METADATA_FETCH`、`PREVIEW_GENERATE`、`SPRITE_GENERATE`、`ARCHIVE_INDEX`、（预留）`TRANSCODE`。

**`upload_session` / `upload_chunk`**
```
upload_session: id, user_id, target_library_id, filename, total_size, chunk_size,
                total_chunks, content_hash, status, created_at, completed_at
upload_chunk:   session_id, chunk_index, size, received_at   (复合主键)
```

**`tag`**：`id, domain, name, slug`，`(domain, slug)` 唯一。视频标签与图片标签互不混用。

### 6.3 视频域

**`media_item`** —— 一个"作品"
```
id, library_id
domain                   恒为 'VIDEO'，复合外键钉死在 libraries.domain 上（见 §5.1）
folder_id                → video_folder，取该条目 PRIMARY 文件所在目录的节点
item_type                MOVIE | SERIES | SINGLE_VIDEO | VIDEO_SERIES
structure                FLAT | GROUPED        ← 独立字段，不由 item_type 推导
title, original_title, sort_title, summary
release_date, rating, cover_asset_id(→derived_asset)
metadata jsonb           类型特有字段（导演、演员、制作方…）
raw_metadata jsonb       刮削器原始响应，原样保留
field_sources jsonb      {"title":"TMDB","summary":"USER"}
locked_fields text[]     用户锁定的字段，任何刮削不得覆盖
scrape_status            NOT_APPLICABLE | PENDING | MATCHED | NO_MATCH | NEEDS_REVIEW | ERROR
scrape_source, scrape_source_id
search_vector tsvector   PG 全文检索
```

**关键设计**：`structure` 是独立字段而非由 `item_type` 推导。一部"电影"若实际含多个部分，同样可以是 `GROUPED`。扫描时按实际目录结构判定，用户可手动更改。

**`media_group`** —— 可选分组（季 / 分册），仅 `structure=GROUPED` 时存在
`id, item_id, group_index, name, summary, cover_asset_id, metadata jsonb`

**`video_file`** —— 语义层
```
id, scanned_file_id(uniq), item_id, group_id(nullable)
role                     PRIMARY | VERSION | EXTRA | SUBTITLE | TRAILER
episode_index            集号（GROUPED 时使用）
duration_seconds, width, height
video_codec, audio_codec, bitrate, container
probe_raw jsonb          ffprobe 原始输出
```
`item_id` 必填、`group_id` 可空——外键单一，不需要"隐式分组"这类绕弯设计。

**`collection`** —— 视频域专属的跨条目聚合
```
collection:      id, library_id, domain(恒为 'VIDEO'，复合外键约束), name, sort_key,
                 summary, cover_asset_id, metadata jsonb
collection_item: collection_id, media_item_id, sort_order   (复合主键)
```
**多对多**：一部电影可同时属于「指环王三部曲」与「托尔金改编作品」。嵌套合集暂不做，理由与将来加法记入 ADR。

**`video_folder`** —— 目录树浏览视图（派生索引，非主模型）
```
id, library_id, parent_id, materialized_path, depth
name, sort_key
direct_item_count, total_item_count      扫描时增量维护
status
```
视频域的主浏览方式是语义化的（按电影/剧集/合集），目录树是**次要视图**，让用户能按自己的目录组织方式浏览。该表只承载导航，不承载元数据与进度。

**`media_item_tag`**：`item_id, tag_id`

### 6.4 图片域

图片内容的组织方式高度个人化——同人图 `画师/年份/合集/`、汉化漫画 `作者/系列/单行本/卷`、表情包 `来源/主题/`——深度各不相同。因此图片域采用**任意深度的节点树**，参照 Perfect Viewer 的交互模型：系统不替用户决定层级。

**`image_node`**
```
id, library_id
domain                   恒为 'IMAGE'，复合外键钉死在 libraries.domain 上（见 §5.1）
parent_id                自引用，根节点为 null
materialized_path        '/1/17/93/'，子树查询用前缀索引，面包屑直接解析
depth
name, sort_key           sort_key 为预计算的自然排序键
source_kind              DIRECTORY | ARCHIVE      ARCHIVE 即 CBZ/ZIP 叶子
reading_mode             AUTO | FORCE_BOOK | FORCE_FOLDER    用户覆盖自动判定
direct_page_count        直属图片数    ┐
child_node_count         直属子节点数  ├ 扫描时增量维护，不做实时递归统计
total_page_count         子树聚合      ┘
cover_asset_id           → derived_asset
title, summary, metadata jsonb, raw_metadata jsonb
field_sources jsonb, locked_fields text[]
scrape_status, scrape_source, scrape_source_id
search_vector tsvector
status                   ACTIVE | MISSING
```

**核心设计：「书」与「文件夹」不是互斥的节点类型，而是同一节点的两种能力。**

- `direct_page_count > 0` → 该节点**可阅读**，进入阅读器
- `child_node_count > 0` → 该节点**可浏览**，进入子项网格
- 两者皆大于 0 → **同时提供两个入口**（一个目录既有散图又有子目录，Perfect Viewer 正是如此处理）
- `reading_mode` 允许用户随时推翻自动判定：把目录强制当作一本书，或把一本书拆开当文件夹浏览

**`image_file`** —— 语义层
```
id, scanned_file_id, node_id, page_index
archive_entry_name       非空表示来自压缩包内条目
width, height, format, is_animated
```
- 散图目录：每张图一个 `scanned_file`，`archive_entry_name` 为 null
- CBZ：一个 `scanned_file` 对应 N 个 `image_file`，各带 `archive_entry_name`

**页不建树节点。** 一本 500 页的漫画若为每页建节点，树会被撑爆；页只是挂在节点下的 `image_file`。

**`image_node_tag`**：`node_id, tag_id`

**决策：`Collection` 不进入图片域。** 树本身已表达层级聚合，再叠一层合集是冗余。

**两个域的组织模型不对称是有意为之**：视频域语义强，"一部电影""一季"是刮削与播放的天然单位，树形会弱化它；图片域组织高度个人化，自由才是需求。树的**遍历算法**（路径维护、子树移动、面包屑、自然排序）在 `shared.MaterializedPathSupport` 中复用，但**两个域各用各的表**——复用算法，不复用模型。

### 6.5 用户态数据

用户态数据独立成表，绝不塞进媒体表。这是多用户设计的核心。

```
video_progress:  user_id, video_file_id, position_seconds, duration_seconds,
                 completed, updated_at                        (复合主键)
image_progress:  user_id, image_node_id, page_index, updated_at   (复合主键)
video_favorite:  user_id, media_item_id, created_at            (复合主键)
image_favorite:  user_id, image_node_id, created_at            (复合主键)
```

`image_favorite` 允许收藏任意节点，**包括文件夹**。

### 6.6 刮削候选

```
scrape_candidate: id
                  media_item_id (nullable, → media_item)
                  image_node_id (nullable, → image_node)
                  provider, external_id, title, year, score, payload jsonb, created_at
                  CHECK (num_nonnulls(media_item_id, image_node_id) = 1)
```
`scrape_status = NEEDS_REVIEW` 时的候选列表，供用户在界面上一键确认或忽略。与 `share_link` 一致，使用两个可空外键而非多态列，使删除条目时候选记录能由外键级联清理。

## 7. 核心链路

### 7.1 扫描对账（含改名检测）

本项目技术含量最高的一条链路。

1. 触发（手动或 `scan_cron`）→ 创建 `LIBRARY_SCAN` job（`dedup_key` 防重复入队）
2. Worker 取 job → `Files.walkFileTree` 遍历 `library.root_path`
3. 对每个文件计算**快速指纹** `(relative_path, size_bytes, mtime)`，与 `scanned_file` 比对：
   - **新增** → 插入 `scanned_file`
   - **变更**（size 或 mtime 改变）→ 更新，标记需重新处理
   - **消失** → 标记 `status = MISSING`，**不删除**。文件很可能只是所在外接盘未挂载，直接删除会连带丢失用户进度。
4. **改名与移动检测**：将本轮"消失"与"新增"的文件按 `content_hash` 配对。
   - **采样哈希**：大文件不做全量哈希，取 `首 1MB + 尾 1MB + size` 计算摘要。全量哈希一个 20GB 的视频文件在机械盘上需要数分钟，对扫描是不可接受的。
   - 配对成功 → 判定为移动，**仅更新 `relative_path`**，`scanned_file.id` 不变 → 语义层与用户进度全部无损保留。
   - 发布 `ScannedFileRelocated` 事件。
5. 发布 `ScannedFileDiscovered` 事件 → 按 `library.domain` 分派给 `video` 或 `image` 的 SPI 实现，由其完成语义层构建（文件名解析、归入 Item/Group 或树节点、推断 Collection）。
6. 增量维护 `video_folder` / `image_node` 的计数字段。
7. 发布 `LibraryScanCompleted` 事件。

**必须处理的三个坑：**

- **符号链接成环**：限制最大遍历深度（32），并记录已访问的**真实路径**（`toRealPath()`）。一个指向祖先目录的软链会让扫描器无限递归。
- **子树移动**：树节点更换父节点时，必须以一条前缀替换 UPDATE 重写整个子树的 `materialized_path`，不可逐层递归。
- **自然排序**：`sort_key` 在写入时预计算。字典序会把 `1, 2, 10` 排成 `1, 10, 2`，这是必踩的坑。

### 7.2 元数据获取

**设计原则：刮削是可选增强，不是必经关卡。** 大量内容（个人录像、自制视频、同人图集、冷门汉化）在 TMDB / Bangumi 中根本不存在，系统必须优雅接纳这一事实。

七条规则：

1. **无刮削亦完全可用。** 扫描完成的瞬间，每个条目已有标题（文件名解析）、封面（视频抽帧 / 漫画首页 / 图集首图），可播放可阅读。刮削是往上加，不是前置条件。
2. **字段级来源追踪 + 用户锁定。** 优先级 `用户编辑 > 本地元数据文件 > 刮削 > 文件名推断`。每个字段的来源记录在 `field_sources`，用户改过的字段进入 `locked_fields`，**任何刮削不得覆盖**。
3. **刮削器按库配置，非全局开关。** `libraries.metadata_providers` 为空数组即该库不刮削，其条目 `scrape_status = NOT_APPLICABLE`，界面零刮削噪音。
4. **匹配带置信度。** Provider 返回候选列表与评分。高分自动应用；低分写入 `scrape_candidate` 并置 `NEEDS_REVIEW`，交由用户确认或忽略。**绝不在低置信度下强行写入。**
5. **找不到是正常状态，不是错误。** `NO_MATCH` 在界面上安静地回落到文件名元数据，不显示为错误。只有网络故障与限流才置 `ERROR` 并进入重试。
6. **本地元数据文件优先。** 支持读取同目录的 `.nfo`（Kodi / Jellyfin 标准）或 `metadata.json`。自制内容可自行编写元数据，不依赖任何外部服务。**这同时解决了演示数据的难题：seed 数据全部依靠本地 NFO 提供元数据，`docker compose up` 无需任何 API key。**
7. **可插拔链式尝试。** `MetadataProvider` 接口，按库配置顺序依次尝试。

**Provider 实现：**

| Provider | 适用 | 鉴权 | 备注 |
|---|---|---|---|
| `LocalNfoProvider` | 全部 | 无 | 读 `.nfo` / `metadata.json`，优先级最高 |
| `TmdbProvider` | 电影、剧集 | **需免费注册申请 API key** | 非商业免费，要求署名归属 |
| `BangumiProvider` | 番剧、漫画 | **无需鉴权**（已实测：`GET /v0/subjects/{id}` 与 `POST /v0/search/subjects` 均返回 200） | Base URL `https://api.bgm.tv/v0/`，须携带标识性 `User-Agent` |
| `FilenameProvider` | 全部 | 无 | 兜底，永远成功 |

外部调用需带客户端侧限流与结果缓存，避免触发对方风控。

### 7.3 视频流式传输

```
GET /api/video/stream/{fileId}
Range: bytes=1000-
```

1. 鉴权：校验用户对该 `library` 的访问权（分享链接令牌走同一入口）
2. 解析 `Range` → 返回 `206 Partial Content`，携带 `Content-Range`、`Accept-Ranges: bytes`、`ETag`、`Content-Length`
3. 完整语义覆盖：`If-Range` 校验、开放式 Range（`bytes=1000-`）、末尾 Range（`bytes=-500`）、非法或越界 Range 返回 `416 Range Not Satisfiable`
4. `FileChannel.transferTo` 零拷贝写出
5. 虚拟线程承载阻塞 I/O

多重 Range（`bytes=0-99,200-299`）返回 `multipart/byteranges` —— 浏览器 `<video>` 实际不发送多重 Range，实施计划中定为**可选项**，若时间紧张则明确返回整个范围的并集。

### 7.4 图片流式阅读

CBZ 本质是 ZIP。**绝不解压到磁盘。**

1. 首次打开时创建 `ARCHIVE_INDEX` job，用 `ZipFile` 建立页索引（entry 名 + 顺序）写入 `image_file`，避免每次访问重新打开压缩包扫描目录区
2. 页序使用**自然排序**（`1.jpg, 2.jpg, 10.jpg`），不可用字典序
3. `GET /api/image/page/{fileId}` → `ZipFile.getInputStream(entry)` 随机访问按需读单页并流式输出
4. 可选按 `Accept` 头转 WebP 以压缩传输体积
5. 预读下一页

散图目录则直接读文件，走同一接口，对前端透明。

### 7.5 任务队列

**为什么不用消息队列**：单实例部署、任务量小、需要可查询可重放的任务历史、避免额外运维复杂度。PostgreSQL 任务表全部满足，且少一个中间件。此决策写入 ADR。

- **抢占**：`SELECT ... FOR UPDATE SKIP LOCKED LIMIT n` —— PostgreSQL 的核心特性，多 worker 并发取任务互不阻塞
- **租约**：`lease_owner` + `lease_expires_at`。worker 崩溃后租约过期，任务被重新抢占，不会永久卡在 `RUNNING`
- **重试**：指数退避，`attempts` 达到 `max_attempts` 后置 `FAILED` 并保留 `last_error`
- **去重**：`dedup_key` 唯一约束，防止同一个库被重复排入扫描任务
- **调度**：`@Scheduled` 轮询 + 虚拟线程执行器

### 7.6 分片上传

1. `POST /api/upload/sessions` 携带 `filename, total_size, content_hash, target_library_id`
2. **秒传**：若 `content_hash` 已存在于 `scanned_file`，直接建立引用并返回完成
3. **断点续传**：`GET /api/upload/sessions/{id}` 返回已接收的 `chunk_index` 列表，客户端只补传缺失分片
4. `PUT /api/upload/sessions/{id}/chunks/{index}` 逐片上传，写入临时目录
5. 全部分片到齐 → 按序合并 → 校验整体哈希 → 移入目标库路径 → 触发增量扫描

### 7.7 搜索（中文分词问题）

**PostgreSQL 内置全文检索不切分中文。** `to_tsvector('simple', '进击的巨人')` 得到的是一个整块 token，搜索"巨人"匹配不上。本项目内容以中文为主，直接套用 `tsvector` 方案会失效——这一点必须在实施前明确，否则会做出一个看起来能跑、实际搜不到东西的功能。

三种方案的取舍：

| 方案 | 中文效果 | 代价 |
|---|---|---|
| **`pg_trgm` + GIN 三元组索引**（选用） | 好。按字符三元组切分，天然支持中文子串匹配与模糊搜索 | `pg_trgm` 是 PostgreSQL 官方 contrib 模块，预期官方镜像自带，`CREATE EXTENSION pg_trgm` 即可。**⚠ 未实测**：撰写本文档时本机 Docker 守护进程未运行，此项列为 P0 验收动作（见下） |
| `zhparser` / `pg_jieba` 分词扩展 | 最好。真正的词法切分 | 需自建 PostgreSQL 镜像，破坏"一键启动"的交付目标 |
| `LIKE '%关键词%'` | 可用 | 全表扫描，无索引 |

**决策**：用 `pg_trgm` 的 GIN 索引做主搜索路径，覆盖中文；对拉丁文内容额外保留 `tsvector` 列以支持词干化与相关度排序。二者结果合并排序。

因此 §6.3 / §6.4 中的 `search_vector tsvector` 列改为：

```sql
-- 拉丁文路径：生成列，无需触发器维护
search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(title,'') || ' ' || coalesce(summary,''))
) STORED

-- 中文主路径：三元组索引直接建在原文列上
CREATE INDEX idx_item_title_trgm ON media_item USING gin (title gin_trgm_ops);
CREATE INDEX idx_node_title_trgm ON image_node USING gin (name gin_trgm_ops);
```

**P0 必须执行的验收动作**（在写任何搜索代码之前）：

```bash
docker run --rm -e POSTGRES_PASSWORD=t -d --name pgtest postgres:17
docker exec pgtest psql -U postgres -v ON_ERROR_STOP=1 \
  -c "CREATE EXTENSION pg_trgm;" \
  -c "SELECT similarity('进击的巨人','巨人');" \
  -c "SELECT to_tsvector('simple','进击的巨人');"
docker rm -f pgtest
```

预期：`CREATE EXTENSION` 成功；`similarity` 返回大于 0 的相似度；`to_tsvector` 返回单个未切分 token（即证实中文分词失效，反向支持本决策）。**若 `CREATE EXTENSION pg_trgm` 失败，则必须改用自建镜像方案并回头修订本节。**

这条取舍写入 ADR，它比"我用了 Elasticsearch"更能体现判断力：**先确认内置能力的真实边界，再决定要不要引入新组件。**

## 8. 前端

后端方向的项目，前端目标是"能演示、不丢人"，不追求精美，追求功能完整与截图效果。

- Vue 3 + Vite + TypeScript，构建产物打包进 Spring Boot 静态资源，单容器交付
- 视频播放：原生 `<video>` + 自定义控制条（进度条悬停显示雪碧图预览帧）
- 漫画阅读：自实现翻页 / 连续滚动 / 双页模式 / 翻页方向切换
- 两个域两套布局与视觉语言，见 §5.4
- **实施时调用 `frontend-design` 技能**（已安装：`frontend-design@claude-plugins-official`，user 作用域）

## 9. 测试策略

| 层次 | 手段 | 覆盖对象 |
|---|---|---|
| 架构测试 | `ApplicationModules.of(...).verify()` | 模块边界，一行代码强制 §4.2 的依赖规则 |
| 单元测试 | JUnit 5 | 文件名解析器、Range 解析器、扫描对账算法、自然排序键、采样哈希、物化路径运算 |
| 模块测试 | `@ApplicationModuleTest` | 单模块独立启动，验证模块自治 |
| 集成测试 | Testcontainers + PostgreSQL 17 | 迁移脚本、复杂查询、任务队列的 `SKIP LOCKED` 并发行为 |
| API 测试 | MockMvc | 鉴权、Range 语义、错误码 |

纯逻辑部分（解析、对账、排序、哈希）是测试重点——它们既是缺陷高发区，也是最容易写出高质量测试的部分。

## 10. 交付物

1. **可运行代码**，`docker compose up` 一键启动（app + postgres 两个容器，ffmpeg 烘焙进 app 镜像）
2. **版权安全的 seed 数据** —— 简历仓库中出现盗版内容是灾难性的，此项为硬性要求：
   - 视频：Blender 开源电影（Big Buck Bunny、Sintel 等，CC-BY）
   - 图片：Unsplash / Pexels 的 CC0 素材
   - 漫画：公有领域作品
   - 全部配套编写 `.nfo`，使演示无需任何 API key
3. **README**：Modulith 自动生成的模块结构图 + 手绘数据流图 + 功能截图/GIF + 快速开始 + 设计决策摘要
4. **`docs/adr/`** 架构决策记录
5. **`docs/walkthrough/`** 每阶段面向项目所有者的讲解文档：这一段做了什么、为什么这么做、坑在哪
6. **项目完工后的逐模块串讲** —— 已与所有者约定，计划中预留此环节

## 11. 实施路线图

每个阶段结束时都必须有可演示的产出。

| 阶段 | 内容 |
|---|---|
| **P0** | 项目骨架：Boot 4.1 + Modulith + PG + Flyway + Docker Compose + 架构测试 + CI。**验收门槛**：① `mvn verify` 通过；② §7.7 的 `pg_trgm` 验证脚本通过 |
| **P1** | 账号、角色、认证、媒体库定义与访问控制 |
| **P2** | 任务队列基础设施：job 表、`SKIP LOCKED` 调度器、租约、重试退避 |
| **P3** | 扫描框架：`scanned_file` 对账、采样哈希改名检测、SPI 定义 |
| **P4** | 视频域语义模型 + 文件名解析 + `video_folder` 目录树视图 |
| **P5** | 视频 Range 流式播放 + 播放进度 + 继续观看 |
| **P6** | 图片域节点树 + CBZ 索引与支持 |
| **P7** | 图片流式阅读 + 阅读进度 + 阅读模式覆盖 |
| **P8** | `preview` 预览生成：ffprobe 探测、封面抽帧、缩略图、进度条雪碧图 |
| **P9** | 元数据刮削链：LocalNfo → Filename → TMDB / Bangumi + 待确认队列 |
| **P10** | 搜索（PG 全文检索）、标签、收藏、分享链接 |
| **P11** | 分片上传 / 断点续传 / 秒传 |
| **P12** | 前端（调用 `frontend-design` 技能） |
| **P13** | seed 数据 + README + ADR + 讲解文档 |
| **P14** | *可选*：转码 / HLS |

## 12. ADR 清单

以下决策需在 `docs/adr/` 中各成一篇，它们是面试中最可能被追问的点：

1. 为什么选模块化单体而非微服务或普通分层单体
2. 为什么用 PostgreSQL 任务表而非消息队列
3. 为什么选 PostgreSQL 而非 MySQL（JSONB 存异构刮削结果、`SKIP LOCKED`、`pg_trgm`）
3b. 为什么中文搜索用 `pg_trgm` 而非 `tsvector` 或 Elasticsearch（见 §7.7）
4. 为什么物理层与语义层分离（改名无损保留用户进度）
5. 为什么两个域使用不对称的组织模型（语义树 vs 自由树）
6. 为什么按领域切分 API 而非按技术层
7. 为什么改名检测使用采样哈希而非全量哈希
8. 为什么刮削是可选增强而非必经步骤
9. 为什么不引入 Redis / Elasticsearch
10. 为什么使用虚拟线程

## 13. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 执行 agent 按 Boot 3.x 记忆生成依赖，全盘编译失败 | 实施计划内联实测生成的完整 `pom.xml`，要求照抄；P0 以编译通过为验收门槛 |
| 项目所有者无法解释自己简历上的项目 | 每阶段产出讲解文档；ADR 覆盖全部可追问点；完工后逐模块串讲 |
| 范围蔓延导致烂尾 | §2 非目标清单为硬约束；P14 之外的功能一律拒绝 |
| 演示数据涉及版权 | seed 数据仅限 CC-BY / CC0 / 公有领域，写入 P13 验收标准 |
| TMDB 需要 API key，他人无法复现刮削 | 演示数据依赖本地 NFO；刮削为可选路径，缺 key 时优雅降级为 `NOT_APPLICABLE` |
| 大媒体库扫描性能不可接受 | 采样哈希；快速指纹先行比对；计数字段增量维护；子树查询走物化路径前缀索引 |

## 14. 待定项

以下项在实施计划阶段定稿，均不阻塞当前设计：

- 认证形态：会话 Cookie 或 JWT
- 多重 Range 请求是否实现（默认不实现，返回并集）
- 嵌套 Collection（当前明确不做，理由与将来加法记入 ADR #5 的延伸）
