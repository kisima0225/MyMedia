package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JobClaimServiceTest extends AbstractIntegrationTest {

    @Autowired
    JobClaimService claimService;

    @Autowired
    JobQueue jobQueue;

    @Test
    void claimedJobsBecomeRunningWithLease() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());

        List<Job> claimed = claimService.claim("worker-1", 10, Duration.ofMinutes(5));

        assertThat(claimed).extracting(Job::getId).contains(id);

        Job job = jobQueue.findById(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(job.getLeaseExpiresAt()).isNotNull();
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        int jobCount = 40;
        for (int i = 0; i < jobCount; i++) {
            jobQueue.enqueue("PREVIEW_GENERATE", "{\"n\":" + i + "}", null);
        }

        int workers = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Callable<List<Long>>> tasks = java.util.stream.IntStream.range(0, workers)
                    .<Callable<List<Long>>>mapToObj(w -> () ->
                            claimService.claim("worker-" + w, 20, Duration.ofMinutes(5))
                                    .stream().map(Job::getId).toList())
                    .toList();

            List<Future<List<Long>>> futures = pool.invokeAll(tasks);

            List<Long> allClaimed = futures.stream()
                    .flatMap(f -> {
                        try {
                            return f.get().stream();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            Set<Long> distinct = allClaimed.stream().collect(Collectors.toSet());

            // 核心断言：并发抢占的结果集必须互不相交。
            // 若 SKIP LOCKED 缺失，同一个任务会被多个 worker 同时拿到。
            assertThat(allClaimed).hasSize(distinct.size());
        }
    }

    @Test
    void expiredLeasesAreReclaimed() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());

        // 租约设为负时长，使其立即过期，模拟 worker 崩溃
        claimService.claim("dead-worker", 10, Duration.ofSeconds(-1));
        assertThat(jobQueue.findById(id).getStatus()).isEqualTo(JobStatus.RUNNING);

        int reclaimed = claimService.reclaimExpiredLeases();

        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        Job job = jobQueue.findById(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getLeaseOwner()).isNull();
    }

    @Test
    void staleWorkerCannotUpdateJobAfterLeaseIsReclaimedAndReassigned() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());

        assertThat(claimService.claim("worker-a", 100, Duration.ofSeconds(-1)))
                .extracting(Job::getId)
                .contains(id);
        claimService.reclaimExpiredLeases();
        assertThat(claimService.claim("worker-b", 100, Duration.ofMinutes(5)))
                .extracting(Job::getId)
                .contains(id);

        claimService.recordSuccess(id, "worker-a");
        claimService.recordFailure(id, "worker-a", "过期 worker 失败");

        Job job = jobQueue.findById(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(job.getAttempts()).isEqualTo(2);
        assertThat(job.getLastError()).isNull();
    }

    @Test
    void jobsScheduledInFutureAreNotClaimed() {
        // markFailed 会把 scheduled_at 推到将来，这类任务不应被立即重新抢占
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());
        claimService.claim("worker-1", 10, Duration.ofMinutes(5));
        claimService.recordFailure(id, "worker-1", "网络超时");

        List<Job> claimed = claimService.claim("worker-2", 10, Duration.ofMinutes(5));

        assertThat(claimed).extracting(Job::getId).doesNotContain(id);
    }
}
