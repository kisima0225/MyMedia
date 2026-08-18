# Task 2 实现报告：扩展名白名单

## 实现内容

- 新增 public `com.mymedia.scan.spi.MediaKind`，包含 `VIDEO`、`IMAGE`、`ARCHIVE`、`IGNORED` 四个枚举值。
- 新增 package-private `MediaExtensions`，按明确白名单将视频、图片、CBZ/ZIP 归类，其余文件返回 `IGNORED`。
- 扩展名使用 `Locale.ROOT` 统一为小写；多段文件名取最后一个扩展名；以 `.` 开头的文件名按隐藏文件处理并返回空扩展名。
- 未引入 Tika、`Files.probeContentType` 或其他内容嗅探，分类仅依赖文件名。
- 保持 Task 1 的 `scan` 模块依赖声明 `shared`、`library`、`jobs` 不变。

## 文件

- `src/main/java/com/mymedia/scan/spi/MediaKind.java`
- `src/main/java/com/mymedia/scan/MediaExtensions.java`
- `src/test/java/com/mymedia/scan/MediaExtensionsTest.java`

## TDD 证据

### RED

命令：

```text
mvn -B -ntp test -Dtest=MediaExtensionsTest -DfailIfNoTests=false
```

关键输出：

```text
[ERROR] .../MediaExtensionsTest.java:[3,28] package com.mymedia.scan.spi does not exist
[ERROR] ... cannot find symbol: class MediaKind
[ERROR] Failed to execute goal ... maven-compiler-plugin ... Compilation failure
[INFO] BUILD FAILURE
```

测试先于生产代码运行，并因目标枚举/分类器不存在而在 testCompile 阶段失败。

### GREEN

focused 命令：

```text
mvn -B -ntp test -Dtest=MediaExtensionsTest -DfailIfNoTests=false
```

关键输出：

```text
[INFO] Running com.mymedia.scan.MediaExtensionsTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 测试结果

- focused `MediaExtensionsTest`：22 run，0 failures，0 errors。
- `mvn -B -ntp test`：72 run，0 failures，0 errors，`BUILD SUCCESS`。
- 22 个执行测试由 12 个已知扩展、8 个忽略项和 2 个普通测试组成；按实际执行结果处理 brief 中的 21/22 计数矛盾。

## 自审

- 视频白名单为 `mkv`、`mp4`、`avi`、`mov`、`wmv`、`flv`、`webm`、`m4v`、`mpg`、`mpeg`、`ts`、`m2ts`。
- 图片白名单为 `jpg`、`jpeg`、`png`、`gif`、`webp`、`avif`、`bmp`、`tiff`、`tif`；归档白名单为 `cbz`、`zip`，未加入 RAR 等额外格式。
- `MediaExtensions` 及其方法保持 package-private；`MediaKind` 是唯一 public 分类承诺。
- `extensionOf(".hidden")` 返回空串，`.DS_Store` 因此被忽略；分类不读取文件内容。
- `git diff --cached --check` 无输出，暂存内容仅为本任务产物；未修改主 worktree `D:\MyMedia`。

## Concerns

- 全量测试输出包含既有 Spring Framework `ContainerConfig`、Java 25/Mockito agent 等 warning，以及测试主动触发的日志；本任务未新增失败，72 个测试全部通过。
