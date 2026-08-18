# Task 11 实施报告：SKIP LOCKED 并发抢占与租约

## 实现摘要

- 新增 package-private `JobClaimService`，以 `REQUIRES_NEW` 事务在一次事务内调用 `claimBatch` 并将返回任务标记为 `RUNNING`，写入 owner、租约到期时间和尝试次数。
- 新增过期租约回收、成功记录和失败记录操作。
- `recordFailure` 保留 brief 规定的 `BASE_BACKOFF=30s`，按 `30s、60s、120s...` 指数退避，最多按 10 次移位封顶；最终尝试由既有 `Job.markFailed` 终结为 `FAILED`。
- 新增 4 项 PostgreSQL 集成测试，验证抢占状态、4 worker 并发互斥、租约回收和未来调度过滤。
- 新增 ADR-003，记录使用 PostgreSQL 任务表和 `FOR UPDATE SKIP LOCKED` 替代消息队列的决策。
- 未修改 Task 10 的实体、repository、迁移或测试配置；`ddl-auto` 仍为 `validate`。

## 文件

- `src/main/java/com/mymedia/jobs/JobClaimService.java`
- `src/test/java/com/mymedia/jobs/JobClaimServiceTest.java`
- `docs/adr/ADR-003-用数据库任务表替代消息队列.md`
- `.superpowers/sdd/2026-08-17-01-infrastructure/task-11-report.md`

## TDD 与回归证据

### RED

命令：

```text
mvn -B -ntp test -Dtest=JobClaimServiceTest -DfailIfNoTests=false > "t.log" 2>&1; $exitCode = $LASTEXITCODE; Write-Output "EXIT=$exitCode"; exit $exitCode
```

关键输出：

```text
EXIT=1
[ERROR] ...JobClaimServiceTest.java:[22,5] 找不到符号
[ERROR] 符号:   类 JobClaimService
```

失败原因是待实现的 `JobClaimService` 不存在，测试未因测试代码自身错误而失败。

### GREEN

同一命令在实现服务后真实执行，捕获到：

```text
EXIT=0
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 回归

命令：

```text
mvn -B -ntp test > "regression.log" 2>&1; $exitCode = $LASTEXITCODE; Write-Output "EXIT=$exitCode"; exit $exitCode
```

关键输出：

```text
EXIT=0
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

回归日志中出现的 PostgreSQL `duplicate key value violates unique constraint "libraries_root_path_key"` 是既有 `rejectsDuplicateRootPath` 测试故意触发并断言的约束异常；该测试及整套回归均通过，不属于本任务失败。

## SKIP LOCKED 并发证据

- 既有 `JobRepository.claimBatch` 使用 `WHERE status = 'PENDING' AND scheduled_at <= :now`，并以 `FOR UPDATE SKIP LOCKED` 锁定候选行。
- `JobClaimService.claim` 标注 `@Transactional(propagation = Propagation.REQUIRES_NEW)`，先查询再在同一事务中调用 `markRunning`，没有拆成查询事务和更新事务。
- `concurrentWorkersNeverClaimTheSameJob` 使用 4 个固定线程并发执行 4 个 worker，每个 worker 请求 batch size 20，预先入队 40 个任务；将所有返回 ID 与 `Set` 比较，断言结果集无重复，真实 PostgreSQL 测试通过。

## 租约与重试证据

- `claimedJobsBecomeRunningWithLease` 验证任务状态变为 `RUNNING`、租约 owner 写为 worker、租约到期时间存在且 attempts 从 0 增为 1。
- `expiredLeasesAreReclaimed` 使用负租约时长模拟 worker 崩溃，验证任务先进入 `RUNNING`，回收后变回 `PENDING` 且 lease owner 清空。
- `jobsScheduledInFutureAreNotClaimed` 通过 `recordFailure` 将失败任务按退避推迟到未来，随后第二个 worker 的 claim 不会取回该任务。
- `recordFailure` 使用 `BASE_BACKOFF` 和尝试次数指数计算下一次调度时间，并委托既有 `Job.markFailed` 处理最大尝试次数和最终失败状态。

## Docker 状态

执行 `docker ps --format "table {{.Names}}\\t{{.Image}}\\t{{.Status}}"` 时：

```text
testcontainers-ryuk-...   testcontainers/ryuk:0.14.0   Up
mymedia-postgres         postgres:17                  Up 2 hours (healthy)
```

Task 11 测试使用 Testcontainers 启动的 PostgreSQL 17，并非 H2 或绕过 schema 的测试配置。

## 自审

- `JobClaimService`、构造器和操作方法均保持 package-private；Spring `@Service` 注册正常，测试已通过注入验证。
- `claim` 的查询、状态变更和事务传播满足 brief；未修改实体、repository SQL、迁移或 `ddl-auto` 配置。
- 临时 `t.log` 与 `regression.log` 已在提交前清理。

## Concerns

- 无阻塞 concerns。按照 brief 严格保留 4 项测试，未额外加入数值化 backoff 时间断言；退避公式和最大尝试次数委托既有 `Job` 状态逻辑，后续 scheduler 任务可继续补充行为覆盖。
