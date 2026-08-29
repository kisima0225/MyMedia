# ADR-010：为什么是 PostgreSQL

## 状态

已接受（2026-08-17，实施计划 08）

## 背景

项目需要一个关系型数据库承载媒体库元数据、扫描状态、任务队列、搜索索引。
候选是 PostgreSQL 与 MySQL 8.0——都免费、都成熟、都能在 Docker Compose
里一键起容器。真正决定选型的不是「哪个更强大」这种空话，而是本项目
**实际用到**的几个特性，MySQL 8.0 能不能干净地覆盖。

## 决策

选 PostgreSQL。逐项对照本项目真实使用的特性：

| 特性 | 落点 | MySQL 8.0 的情况 |
|---|---|---|
| `JSONB` | `video_item.metadata` / `raw_metadata` / `field_sources`（`V6__video_domain.sql:45-47`），异构刮削结果直接存，`VideoMetadataStore` / `ImageMetadataStore` 用 `metadata = metadata \|\| CAST(? AS jsonb)` 做浅合并 | 有 `JSON` 类型，但没有 GIN 索引这套，也没有 `\|\|` 合并运算符 |
| `FOR UPDATE SKIP LOCKED` | `job` 表的抢占（`JobRepository.java` 的抢占查询、`JobClaimService`），ADR-003 的地基 | 8.0 起也有 |
| 部分唯一索引 | `job.dedup_key` 只在 `PENDING`/`RUNNING` 上唯一（`V4__jobs.sql:23` 的 `uq_job_dedup_active`），实现「同一个库在上一轮扫描成功之后可以再排一次」 | **没有**，只能靠额外列 + 触发器模拟 |
| `pg_trgm` + GIN | 中文子串搜索，`VideoSearchService` / `ImageSearchService` 的 `similarity()` 排序（ADR-006） | **没有**，要么全表扫要么上外部搜索引擎 |
| 生成列 + `to_tsvector` | `V12__search_columns.sql` 的 `search_vector` 生成列，拉丁文全文检索路径（词干化） | 有生成列，**没有 tsvector** |
| `TEXT[]` | `libraries.metadata_providers`（`V3__libraries.sql:7`） | **没有数组类型**，只能拆表或存 JSON |
| 复合外键 + `UNIQUE (id, domain)` | 域分区的数据库级强制（ADR-001） | 支持复合外键，但没有 `CHECK` 之外能跨表引用的等价表达 |

## 理由

**七条里 MySQL 只能干净覆盖两条**（`SKIP LOCKED` 与复合外键）。其余五条
要么完全没有等价物（部分唯一索引、`pg_trgm`、数组类型、`tsvector`），
要么有名同实异的近似物但缺关键能力（`JSON` 没有 GIN 索引）。

这不是「PostgreSQL 功能更全」这种泛泛判断，而是这些特性分别落在项目
四条不同的真实链路上：任务队列的去重语义（部分唯一索引）、任务队列的
并发抢占（`SKIP LOCKED`）、中文搜索（`pg_trgm`）、异构刮削结果的存储
（`JSONB`）。抽掉任何一条，对应的功能都得换一种实现方式，而不是「差不多
能凑合」。

## 后果

SQL 因此绑定了 PostgreSQL 方言：`VideoMetadataStore` / `ImageMetadataStore`
/ `ScrapeCandidateStore` 里的 `JdbcTemplate` 写死了 `CAST(? AS jsonb)` 与
`jsonb` 的 `\|\|` 合并运算符，`JobRepository` 写死了 `FOR UPDATE SKIP
LOCKED`，`VideoSearchService` / `ImageSearchService` 写死了 `similarity()`。
换成任何其他数据库都不是配置切换，而是重写整条数据访问层。**这个代价
是明确接受的**——项目的交付形态本来就是「自带 `postgres:17` 容器的
`docker compose up`」，不存在「用户自带 MySQL」这种场景，绑定方言不产生
额外的现实成本。

`JSONB` / `TEXT[]` 列一律不做 JPA 映射（Hibernate 对这两类类型的映射机制
很挑，`ddl-auto=validate` 下容易踩坑），全部走 `JdbcTemplate` 直读直写——
这是从计划 03 起就贯彻的约定，本 ADR 是它在选型层面的根因。

## 备选方案

- **MySQL 8.0**：见上表，七条能力只能干净覆盖两条，中文搜索与任务队列
  去重都要额外造轮子。
- **SQLite**：单文件部署很诱人，且省掉了一个容器，但**没有 `SKIP
  LOCKED`**——`job` 表队列的并发抢占语义直接不成立，整套 ADR-003 的设计
  要推倒重来（换成应用层加锁或单 worker 串行消费，等于放弃并发处理）。
- **加一个专门的搜索引擎（Elasticsearch / MeiliSearch）**：见 ADR-014。
  概括地说：多一个中间件，与「模块化单体、少运维、一键 compose up」的
  定位冲突，且 `pg_trgm` 已经把中文子串搜索覆盖到项目当前规模够用的
  程度（ADR-006 实测：10 万条目内两字查询 29 ms）。
