# Task 1 实现报告：scanned_file 物理层

## 实现内容

- 新增 Flyway V5 `scanned_file` 表，包含库级路径唯一约束、ACTIVE/MISSING 状态约束、级联删除，以及按库/状态和内容哈希查询的索引。
- 按裁决上下文补充 nullable `mime_type`：数据库列为 `VARCHAR(128)`，JPA 实体以 nullable 字段映射；没有加入 public getter，也没有实现内容嗅探。
- 新增 `ScannedFileStatus`、`ScannedFile`，提供 brief 要求的 public getters 和后续扫描流程所需的 package-private 状态变更方法。
- 新增 package-private `ScannedFileRepository`，以及对外公开、只读事务的 `ScannedFileQueryService`：`getById`、`findByPath`、`countActive`。
- `scan/package-info.java` 声明 Modulith `allowedDependencies = {"shared", "library", "jobs"}`。
- 新增 brief 指定的 5 个 PostgreSQL 集成测试。

## 文件

- `src/main/resources/db/migration/V5__scanned_file.sql`
- `src/main/java/com/mymedia/scan/package-info.java`
- `src/main/java/com/mymedia/scan/ScannedFileStatus.java`
- `src/main/java/com/mymedia/scan/ScannedFile.java`
- `src/main/java/com/mymedia/scan/ScannedFileRepository.java`
- `src/main/java/com/mymedia/scan/ScannedFileQueryService.java`
- `src/test/java/com/mymedia/scan/ScannedFileRepositoryTest.java`

## TDD 证据

### RED

命令：

```text
mvn -B -ntp test -Dtest=ScannedFileRepositoryTest -DfailIfNoTests=false
```

关键输出：

```text
[ERROR] .../ScannedFileRepositoryTest.java:[19,5] cannot find symbol
  symbol:   class ScannedFileQueryService
[ERROR] ... maven-compiler-plugin ... Compilation failure
[INFO] BUILD FAILURE
```

测试先于生产代码运行，并因目标 service 不存在而失败。

### GREEN

focused 命令：

```text
mvn -B -ntp test -Dtest=ScannedFileRepositoryTest -DfailIfNoTests=false
```

关键输出：

```text
Successfully applied 6 migrations ... now at version v5
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 测试结果

- focused `ScannedFileRepositoryTest`：5 run，0 failures，0 errors。
- `mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false`：2 run，0 failures，0 errors。
- `mvn -B -ntp test`：50 run，0 failures，0 errors，`BUILD SUCCESS`。
- focused 与全量运行均使用 Testcontainers PostgreSQL 17；Flyway、JPA `ddl-auto=validate` 和新增查询均通过。

## 自审

- SQL 与 brief 保持一致，仅增加裁决要求的 nullable `mime_type` 列及实体映射。
- repository 保持 package-private；service 是 scan 对外的唯一 public 查询入口，未暴露 repository。
- `library_id` 使用 `ON DELETE CASCADE`，同库路径唯一、跨库同路径可用，ACTIVE 计数不会包含 MISSING。
- 未实现目录遍历、扩展名分类、Tika、`probeContentType` 或任何内容嗅探。
- `git diff --check` 无输出；新增代码未修改主 worktree `D:\MyMedia`。

## Concerns

- 测试输出包含既有 Spring Framework 7.1 关于 `AbstractIntegrationTest.ContainerConfig` 将被忽略的 warning，以及 Java 25/Mockito agent warning；本任务未修改这些基础设施，当前测试仍全部通过。
- `mime_type` 当前仅作 nullable 持久化字段，后续任务如需对外暴露或填充应另行定义 API 与分类策略。
