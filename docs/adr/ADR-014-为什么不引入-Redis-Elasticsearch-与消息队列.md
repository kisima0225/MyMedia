# ADR-014：为什么不引入 Redis / Elasticsearch / 消息队列

## 状态

已接受（2026-08-17，实施计划 08）

## 背景

任务队列、全文搜索、缓存是三个经常被默认「要上中间件」的场景——消息
队列配 RabbitMQ/Kafka，搜索配 Elasticsearch，缓存配 Redis 几乎是行业
肌肉记忆。但这三条判断在本项目上**理由同源**：单实例部署（没有第二个
进程需要协调）、可解释性优先（每加一个中间件就多一段「为什么」要能
答上来）、而 PostgreSQL 已经把三条链路各自需要的能力覆盖到位。三条
合成一篇正是因为拒绝它们的论证结构完全一样，分开写会把同一句话说
三遍。

## 决策

不引入 RabbitMQ/Kafka、Elasticsearch、Redis 中的任何一个，逐条列出
本项目实际用什么替代、落在哪些真实文件里：

| 想引入的 | 本项目的替代 | 落点 |
|---|---|---|
| 消息队列 | `job` 表 + `FOR UPDATE SKIP LOCKED` + 租约 + 指数退避重试 | `V4__jobs.sql`（`job` 表定义）；`JobQueue`（入队，`src/main/java/com/mymedia/jobs/JobQueue.java`）；`JobRepository.claimBatch`（实际的 `FOR UPDATE SKIP LOCKED` 原生 SQL，`src/main/java/com/mymedia/jobs/JobRepository.java:26-32`）；`JobClaimService`（在事务内驱动抢占、维护租约、失败后按 `BASE_BACKOFF`=30s 指数退避，`src/main/java/com/mymedia/jobs/JobClaimService.java:19,38-47,78-89`）；`JobScheduler`（定时轮询驱动整条链路，任务本身用虚拟线程执行，见 ADR-015）；详见 ADR-003 |
| Elasticsearch | `pg_trgm` 子串路径（中文） + `tsvector` 生成列路径（拉丁文全文），分层排序取并集 | `V12__search_columns.sql`（`search_vector` 生成列与 GIN 索引）；`VideoSearchService` / `ImageSearchService`；边界见 ADR-006 |
| Redis | Spring 自带的 `ConcurrentMapCacheManager`（刮削结果缓存）；会话根本不存在（HTTP Basic 无状态，ADR-002） | `ProviderCacheConfig`（`src/main/java/com/mymedia/metadata/ProviderCacheConfig.java`），计划 05 Task 10 落地 |

## 理由

三条判断都回到同一个前提：本项目的部署形态是「一个人、一台机器、
`docker compose up`」。消息队列解决的是跨进程/跨节点的可靠投递与
扇出，而单实例内 `FOR UPDATE SKIP LOCKED` 已经提供了并发消费所需的
全部语义（多个 worker 互不阻塞地拿到互不相交的任务集）；Elasticsearch
解决的是大规模语料的分词、相关度与横向扩展，而 `pg_trgm` + `tsvector`
在项目当前的数据量级上把中文子串搜索与拉丁文全文搜索都覆盖了（边界
在 ADR-006 里实测量化过：10 万行表上两字中文查询退化成全表扫描，
29 ms，仍然可用）；Redis 解决的是跨进程共享的、需要淘汰策略的缓存，
而本项目的缓存只服务同一个 JVM 进程内的刮削请求去重，`ConcurrentMap`
足够。三个中间件的核心收益都是「多实例/大规模场景下才兑现」，而这两个
前提在本项目里都不成立——加了等于为不存在的问题预先付费。

## 触发条件：什么时候该改主意

这一篇最容易被误读成「反对一切中间件」，但真正的判断标准不是任务量或
数据量，而是**架构形态是否变了**：

- **消息队列**：出现第二个实例、或者需要跨进程的扇出订阅（比如多个
  worker 进程分布在不同机器上）。任务量本身不是理由——`SKIP LOCKED`
  在单实例上能扛的量远超一个自托管媒体库的真实负载；只要还是「一个
  进程池内的多个 worker 抢同一张表」，就没有理由换成消息队列。
- **Elasticsearch**：语料量级到百万条、或者需要同义词/拼音/分词器这类
  `pg_trgm`/`tsvector` 给不了的真正检索能力。ADR-006 已经量化了当前
  的上界（10 万行、两字查询 29 ms）；量级往上翻一到两个数量级，或者
  用户开始要「拼音搜索」「模糊匹配错别字」这类语义能力时，才是重新
  评估的时机。
- **Redis**：出现多实例需要共享缓存（当前单进程内 `ConcurrentMap`
  天然是进程私有的，多实例下各自为政、互不可见）、或者缓存本身需要
  淘汰策略与持久化。当前 provider 缓存**没有淘汰策略**，这是记在案
  的缺口 G18（内部文档「总览与交接」§5）——不是没意识到，是「先
  接受，等真的成为问题再动」。

## 后果

单实例是这三条判断共同的硬约束，一旦项目要横向扩展到多实例部署，上面
三条**都要重新评估**，不是可以只改其中一条。更具体地，provider 缓存
（`ConcurrentMapCacheManager`）没有淘汰策略，`ProviderCacheConfig`
的类注释（`src/main/java/com/mymedia/metadata/ProviderCacheConfig.java:15-16`）
把这解释为「可以接受」——缓存键是 (提供者, 标题, 年份)，条目数上界就是
媒体库的条目数，一万条目的库也就一万个几百字节的条目。但这个「上界」
只在某一个媒体库规模的快照下成立：缓存本身没有淘汰逻辑，条目被刮削过
一次之后即使对应的作品从库里被删除、重命名，缓存条目也不会被回收，
只会随进程存活时间单调累积、直到进程重启才清零。库规模不变不代表
缓存不再增长——这一条要老实写，是**明确接受、留待观察**的缺口
（G18），不是被忽略的设计漏洞。

## 备选方案

见上表「想引入的」一列——RabbitMQ/Kafka、Elasticsearch、Redis 本身
就是被拒绝的备选方案，具体取舍已在「理由」与「触发条件」两节写清楚，
不再重复列一张同义的表。三者共同的反面论据是：都会让「一键
`docker compose up`」的交付目标多背一个容器、一套监控与一类新的
故障模式，而当前规模下 PostgreSQL 已经覆盖了它们各自要解决的问题。
