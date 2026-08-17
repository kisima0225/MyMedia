# Final Fix Report：基础设施阶段审查修复

日期：2026-08-17
基线：`2d5a34a`

## Findings

### 1. JobQueue 并发去重

- 根因：`JobQueue.enqueue` 先通过 JPA 查询 active 任务，再插入；并发窗口内 PostgreSQL partial unique index 会让败者收到 `DataIntegrityViolationException`。
- 修复：改为 PostgreSQL 原子 `INSERT ... ON CONFLICT (dedup_key) WHERE ... DO UPDATE ... RETURNING id`。冲突行只做同值更新并返回既有 id，保留原任务 payload 和状态；`NULL dedupKey` 仍然每次新建。
- 测试：`JobQueueTest.concurrentEnqueueWithSameDedupKeyReturnsOneIdWithoutErrors` 使用 40 个并发调用，断言 40 个结果全部为同一个 id，Future 没有异常。
- RED：旧实现真实抛出 `DataIntegrityViolationException`，约束为 `uq_job_dedup_active`。
- GREEN：`mvn -B -ntp test -Dtest=JobQueueTest#concurrentEnqueueWithSameDedupKeyReturnsOneIdWithoutErrors -DfailIfNoTests=false`，`EXIT=0`，`BUILD SUCCESS`。

### 2. JobScheduler 异步执行与生命周期

- 根因：`pollOnce` 在调度线程逐个调用 handler，慢 handler 会阻塞后续任务和下一轮调度。
- 修复：使用受控的 virtual-thread-per-task `ExecutorService` 提交每个已领取任务；增加 `@PreDestroy`，关闭时等待任务并在超时后中断。
- 测试：`JobSchedulerTest.slowHandlerDoesNotBlockOtherJobsOrTheNextPoll` 使用阻塞 handler，验证慢任务保持 `RUNNING` 时其他任务成功，下一次 `pollOnce` 仍可领取并完成任务；既有完成/失败/无 handler 测试改为等待异步状态。
- RED：旧实现的慢 handler 测试在 1 秒内 `TimeoutException`。
- GREEN：`mvn -B -ntp test -Dtest=JobSchedulerTest#slowHandlerDoesNotBlockOtherJobsOrTheNextPoll -DfailIfNoTests=false`，`EXIT=0`，`BUILD SUCCESS`。

### 3. 集成测试 scheduler 隔离

- 修复：`JobScheduler` 增加 `@ConditionalOnProperty`，生产默认启用（`matchIfMissing=true`，并在 `application.yml` 显式配置 `mymedia.jobs.enabled: true`）。`AbstractIntegrationTest` 默认设置 `mymedia.jobs.enabled=false`；`JobSchedulerTest` 明确设置为 `true`，并把自动轮询间隔设为 `PT1H`，测试仍通过直接调用 `pollOnce` 验证调度。
- 测试：`JobQueueTest.schedulerIsDisabledForNonSchedulerIntegrationTests` 断言非 scheduler 集成测试上下文没有 scheduler bean；JobSchedulerTest 的显式启用和完整测试均通过。
- 全量测试没有再出现 scheduler 消费其他测试遗留任务的跨测试污染。

### 4. JobQueue 完成 API owner fencing

- 修复：`JobQueue.markSucceeded(Long)` 改为 package-private，不再作为 public API 暴露。
- 实际 scheduler 完成路径仍只调用带 owner 和有效租约校验的 `JobClaimService.recordSuccess`；同包 `JobQueueTest` 继续验证完成后复用 dedupKey。
- `JobClaimServiceTest.staleWorkerCannotUpdateJobAfterLeaseIsReclaimedAndReassigned` 保持旧 worker 无法覆盖新 owner 状态的断言。

### 5. REST 输入与数据库冲突契约

- `LibraryDto.CreateRequest.name` 增加 `@Size(max = 128)`，与 `libraries.name VARCHAR(128)` 对齐；Jackson/MockMvc 绑定仍通过。
- `GlobalExceptionHandler` 增加 `DataIntegrityViolationException` 到 HTTP 409 `ProblemDetail` 的映射，detail 为 `请求与现有数据冲突`。
- `LibraryControllerTest.duplicateRootPathReturnsConflictProblemDetail` 断言 HTTP 409、ProblemDetail content type、status 409 和稳定 detail。
- `LibraryControllerTest.rejectsNameLongerThanDatabaseColumn` 断言超长 name 返回 400。

### 6. Modulith allowedDependencies

- `shared`：显式无依赖。
- `user`：仅允许 `shared`。
- `library`：允许 `shared`、`user`。
- `jobs`：仅允许 `shared`。
- `ModularityTests` 的 `ApplicationModules.verify()` 通过。

### 7. Task 7 覆盖补强

- 复合唯一键测试现在查询 `pg_constraint`、`pg_attribute`，断言约束属于 `libraries` 且列顺序确实为 `(id, domain)`。
- 重复 root path 测试改为断言 `DataIntegrityViolationException`，并核对 PostgreSQL 唯一约束名 `libraries_root_path_key`，不再使用宽泛 `Exception`。

### 8. Task 11 覆盖补强

- 并发抢占测试现在断言恰好 40 个任务被领取，且 distinct id 数也是 40；未削弱 `SKIP LOCKED` 并发测试。
- 新增首次退避时间断言：首次失败后的 `scheduled_at` 约为当前时间加 30 秒。
- 新增三次失败后的最终状态、attempts 等于 maxAttempts、lastError 保留断言，确认最终为 `FAILED`。

### 9. 失败日志措辞

- 将“已记录失败结果”改为“任务处理异常……将尝试更新任务状态”，不再在 owner fencing 或持久化完成前承诺结果已经写入。

### 10. bootstrap ADMIN 自动测试

- `AuthenticationTest.bootstrapAdminCanCallAdminOnlyPost` 使用 bootstrap 的 `admin/admin` 调用 ADMIN-only `POST /api/libraries`。
- root path 使用 UUID 唯一值，断言返回 201 和 rootPath，证明 bootstrap 账号确实拥有 ADMIN 角色且不依赖固定媒体库数据。

### 11. README PowerShell 示例

- 修正 `curl.exe` JSON 示例：PowerShell 单引号字符串内使用实际 JSON 双引号，不再写反斜杠转义。

### 12. LibraryDto 可见性

- `LibraryDto`、`CreateRequest`、`Response` 均改为 library 模块内部可见；controller 与 MockMvc 的 Jackson 绑定和响应测试通过。

## Verification

### 定向测试

- `mvn -B -ntp test -Dtest=JobQueueTest#concurrentEnqueueWithSameDedupKeyReturnsOneIdWithoutErrors -DfailIfNoTests=false`：`EXIT=0`，`BUILD SUCCESS`。
- `mvn -B -ntp test -Dtest=JobSchedulerTest#slowHandlerDoesNotBlockOtherJobsOrTheNextPoll -DfailIfNoTests=false`：`EXIT=0`，`BUILD SUCCESS`。
- `mvn -B -ntp test -Dtest=JobClaimServiceTest -DfailIfNoTests=false`：5 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `mvn -B -ntp test -Dtest=JobSchedulerTest -DfailIfNoTests=false`：4 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `mvn -B -ntp test "-Dtest=LibraryControllerTest,LibraryDomainConstraintTest,AuthenticationTest,ModularityTests" -DfailIfNoTests=false`：19 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 首次未加引号的 PowerShell 多测试类参数被 shell 解析为语法错误；改用引号包裹 `-Dtest` 后重跑成功，不是项目测试失败。

### 完整验收

命令：

```text
mvn -B -ntp verify
```

实际捕获：

```text
EXIT=0
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Testcontainers/Flyway 日志明确显示 `postgres:17` 和 PostgreSQL `17.11`；未使用 H2，也未修改 `ddl-auto: validate` 或绕过 Flyway schema validation。临时 `full-verify.log` 与 `target` 已清理。

### Diff 自审

- `git diff --check`：退出码 0；仅报告 Windows 工作区 LF/CRLF 转换提示，无 whitespace error。
- 检查了 public API、owner-fenced 完成路径、scheduler 条件属性、Flyway 迁移和受影响测试；本轮未删除有效测试。

## 明确保留的裁决

- 不启用 CSRF，保留 HTTP Basic + 禁用 CSRF 的 Task 6/ADR-002 决策。
- 不创建 CI workflow。
- 不创建 video/image 子表复合外键；该范围属于后续计划。

## Concerns

- 无阻塞功能 concerns。构建输出仍有 JDK 25/Mockito 动态 agent 提示，以及 Spring Framework 7.1 关于测试配置发现行为的未来版本提示；本轮未改变这些既有工具链提示，当前 `verify` 仍为 0 failures/errors。

提交 hash：由最终 `git commit` 输出记录在交付结果中。
