# ADR-013：按领域切分 API

## 状态

已接受（2026-08-17，实施计划 08）

## 背景

MyMedia 同时管理视频与图片两个媒体域。API 层面有两条路可走：一套统一的
`/api/media?type=video|image` 端点，用一个 `type` 参数在运行时决定分支；
或者两套完全独立的端点 `/api/video/**`、`/api/image/**`，各自定义自己的
DTO、控制器与服务。项目从计划 03（视频域）到计划 04（图片域）一路走的
是后者，全局搜索是这两条平行体系里唯一需要交汇的地方。

## 决策

`/api/video/**` 与 `/api/image/**` 两套端点并存，不做成一套带 `type`
参数的 `/api/media`。唯一的交汇点是 `GET /api/search`
（`GlobalSearchController`），而且**结果分区返回、不混排**：响应形状是
`GlobalSearchDto.Response(query, video, image)`
（`src/main/java/com/mymedia/web/GlobalSearchDto.java:19`），`video` 与
`image` 是两个独立数组，未命中的一边是空数组而不是省略字段——
`GlobalSearchControllerTest` 里 `aDomainWithNoHitsIsAnEmptyArrayNotAMissingField`
这条用例（`src/test/java/com/mymedia/web/GlobalSearchControllerTest.java:81-87`）
专门验证这一点：查询「剧场版」只命中视频域时，`$.image` 断言的是
`hasSize(0)` 而不是字段缺失。分区不混排本身也有独立用例覆盖：
`returnsTwoPartitionedArraysRatherThanOneMixedList`（同文件 70-78 行）。

## 理由

两个域的响应形状本来就不同——视频条目要带剧集列表与分组结构（季/集、
`groupId`），图片节点要带页数、`readable`/`browsable` 双入口与阅读模式。
一套带 `type` 的端点等于把两种形状塞进一个 DTO：字段要么全量并集（图片
专属字段在视频响应里永远是 `null`），要么客户端拿到手第一件事还是
`if (type === 'video') { ... } else { ... }`——抽象没有省掉这个判断，
只是把它从「两个端点」挪到了「一个端点内部的分支」，位置更差，因为
类型系统也帮不上忙（TypeScript 端同样要靠 `type` 字段做判别）。

前端 `api/video.ts` 与 `api/image.ts` 是这个决策在客户端的直接映射：
两个文件各自定义 `listItems`/`browse`/`searchVideo`（`video.ts`）与
`listRoots`/`pages`/`searchImage`（`image.ts`），彼此不共享一个「媒体」
类型或函数。前端确实还有第三个文件 `frontend/src/api/media.ts`，但它管
的是媒体票据签发与 URL 拼接（`assetUrl`/`mediaUrl`），是横切的资源鉴权
关注点，不是视频/图片的统一领域模型——`frontend/src/api/types.ts` 里
`VideoItemSummary` 与 `ImageNodeSummary` 是两个互不相关的 `interface`，
没有一个 `Media`/`MediaItem` 联合类型把它们粘在一起。

## 后果

代价是控制器逐字对称重复：收藏（`VideoFavoriteController` /
`ImageFavoriteController`）、搜索（`VideoSearchController` /
`ImageSearchController`）、分享各有两套几乎同构的端点。最直接的例子是
`MAX_LIMIT = 100` 这个常量——`GlobalSearchController`、
`VideoSearchController`、`ImageSearchController` 三个类里各写了一遍
`private static final int MAX_LIMIT = 100;` 加同样的
`Math.clamp(limit, 1, MAX_LIMIT)`，没有抽到 `shared`。这不是遗漏：计划
06 的 review 把它明确记为一条 deferred Minor（内部文档「总览与交接」
§11：「`MAX_LIMIT = 100` +
`Math.clamp(limit, 1, MAX_LIMIT)` 在 `GlobalSearchController`、
`VideoSearchController`（Task 2）、`ImageSearchController`（Task 3）
三个控制器里逐字重复，未抽到 `shared`——与本项目『不为几行重复代码搭
抽象』的既有取舍一致，本次不修」）。三行重复的常量声明比一个
`SearchLimits` 工具类更容易读、更不容易在未来被误用到不该用的地方。

## 备选方案

- **统一端点 + `type` 参数**（`/api/media?type=video`）：省下控制器层面
  的重复，但把「两种形状」的判断从「选哪个端点」搬进了「解析响应体」，
  客户端仍然要按 `type` 分支处理，且响应 DTO 要么做并集字段、要么用
  联合类型——对一个只有两个域、形状差异很大的项目，统一端点的收益不
  抵它引入的字段可空性与判别逻辑。
- **GraphQL**：能用一次查询按需拼接两个域的字段，但为一个自托管单实例
  项目引入一整套查询语言、schema 定义与解析器基础设施，「为什么要拆」
  这个问题现阶段答不了——与 ADR-009 拒绝微服务的理由同构：收益在
  当前规模下不成立。
