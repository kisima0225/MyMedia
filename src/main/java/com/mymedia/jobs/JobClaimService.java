package com.mymedia.jobs;

import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
class JobClaimService {

    /** 首次重试等待 30 秒，其后每次翻倍：30s、60s、120s…… */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private final JobRepository repository;

    JobClaimService(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * 抢占一批待执行任务。
     *
     * <p>整个方法必须在一个事务内：{@code FOR UPDATE SKIP LOCKED} 持有的行锁
     * 只在事务期间有效。在同一事务内把状态改成 RUNNING，其他 worker 才看不到
     * 这些行。若把查询与状态更新拆到两个事务，中间的窗口会让任务被重复抢占。
     *
     * <p>{@code REQUIRES_NEW} 保证即使调用方已在事务中，抢占也独立提交，
     * 使租约尽快对其他 worker 可见。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<Job> claim(String owner, int batchSize, Duration leaseDuration) {
        Instant now = Instant.now();
        List<Job> jobs = repository.claimBatch(now, batchSize);
        Instant leaseExpiry = now.plus(leaseDuration);
        for (Job job : jobs) {
            job.markRunning(owner, leaseExpiry);
        }
        return jobs;
    }

    /**
     * 回收租约已过期的任务。worker 进程崩溃时不会有人把任务标记为失败，
     * 租约到期是唯一能察觉它已死的信号。
     *
     * @return 被回收的任务数
     */
    @Transactional
    int reclaimExpiredLeases() {
        return repository.reclaimExpiredLeases(Instant.now());
    }

    @Transactional
    void recordSuccess(Long jobId, String owner) {
        Job job = loadOwnedRunning(jobId, owner);
        if (job != null) {
            job.markSucceeded();
        }
    }

    /**
     * 记录一次失败。未达最大尝试次数则按指数退避推迟重试，否则终结为 FAILED。
     */
    @Transactional
    void recordFailure(Long jobId, String owner, String error) {
        Job job = loadOwnedRunning(jobId, owner);
        if (job == null) {
            return;
        }
        long multiplier = 1L << Math.min(job.getAttempts() - 1, 10);   // 上限约 8.5 小时
        Instant nextAttemptAt = Instant.now().plus(BASE_BACKOFF.multipliedBy(multiplier));
        job.markFailed(error, nextAttemptAt);
    }

    private Job loadOwnedRunning(Long jobId, String owner) {
        Job job = repository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + jobId));
        Instant leaseExpiresAt = job.getLeaseExpiresAt();
        if (job.getStatus() != JobStatus.RUNNING
                || !Objects.equals(owner, job.getLeaseOwner())
                || leaseExpiresAt == null
                || !leaseExpiresAt.isAfter(Instant.now())) {
            log.warn("忽略任务更新 id={}，请求 owner={}，当前 status={}、leaseOwner={}、leaseExpiresAt={}",
                    jobId, owner, job.getStatus(), job.getLeaseOwner(), leaseExpiresAt);
            return null;
        }
        return job;
    }
}
