package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(JobSchedulerTest.TestHandlers.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.jobs.lease-duration=PT0.3S",
        "mymedia.preview.wiring-enabled=false"
})
class JobSchedulerTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue jobQueue;

    @Autowired
    JobScheduler scheduler;

    @Autowired
    RecordingHandler recordingHandler;

    @Autowired
    AlwaysFailingHandler failingHandler;

    @Autowired
    BlockingHandler blockingHandler;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearJobs() {
        jdbc.update("DELETE FROM job");
        recordingHandler.handled().clear();
        blockingHandler.reset();
    }

    @Test
    void dispatchesJobToMatchingHandler() {
        Long id = jobQueue.enqueue("TEST_RECORD", "{\"v\":42}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(recordingHandler.handled()).contains(id));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    void failedJobIsRescheduledForRetry() {
        Long id = jobQueue.enqueue("TEST_FAIL", "{}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Job job = jobQueue.findById(id);
            // 首次失败后应退回 PENDING 等待退避重试，而不是直接判死
            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(job.getLastError()).contains("故意失败");
        });
    }

    @Test
    void jobWithNoHandlerFailsWithClearMessage() {
        Long id = jobQueue.enqueue("NO_SUCH_TYPE", "{}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(id).getLastError())
                        .contains("没有注册").contains("NO_SUCH_TYPE"));
    }

    @Test
    void slowHandlerDoesNotBlockOtherJobsOrTheNextPoll() throws Exception {
        Long slowId = jobQueue.enqueue("TEST_SLOW", "{}", "k" + UUID.randomUUID());
        Long firstFastId = jobQueue.enqueue("TEST_RECORD", "{}", "k" + UUID.randomUUID());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstPoll = caller.submit(scheduler::pollOnce);

            assertThat(firstPoll.get(1, TimeUnit.SECONDS)).isNull();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(blockingHandler.started()).isTrue());
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(recordingHandler.handled()).contains(firstFastId));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jobQueue.findById(firstFastId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));

            Long secondFastId = jobQueue.enqueue("TEST_RECORD", "{}", "k" + UUID.randomUUID());
            scheduler.pollOnce();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(recordingHandler.handled()).contains(secondFastId));
            assertThat(jobQueue.findById(slowId).getStatus()).isEqualTo(JobStatus.RUNNING);
        } finally {
            blockingHandler.release();
            caller.shutdown();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jobQueue.findById(slowId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
        }
    }

    @Test
    void renewsLeaseWhileSlowHandlerRuns() {
        Long slowId = jobQueue.enqueue("TEST_SLOW", "{}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(blockingHandler.started()).isTrue());
        Instant originalExpiry = jobQueue.findById(slowId).getLeaseExpiresAt();

        try {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(jobQueue.findById(slowId).getLeaseExpiresAt())
                            .isAfter(originalExpiry));
        } finally {
            blockingHandler.release();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jobQueue.findById(slowId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
        }
    }

    @TestConfiguration
    static class TestHandlers {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        AlwaysFailingHandler alwaysFailingHandler() {
            return new AlwaysFailingHandler();
        }

        @Bean
        BlockingHandler blockingHandler() {
            return new BlockingHandler();
        }
    }

    static class RecordingHandler implements JobHandler {

        private final List<Long> handled = new CopyOnWriteArrayList<>();

        @Override
        public String jobType() {
            return "TEST_RECORD";
        }

        @Override
        public void handle(Job job) {
            handled.add(job.getId());
        }

        List<Long> handled() {
            return handled;
        }
    }

    static class AlwaysFailingHandler implements JobHandler {

        @Override
        public String jobType() {
            return "TEST_FAIL";
        }

        @Override
        public void handle(Job job) {
            throw new IllegalStateException("故意失败");
        }
    }

    static class BlockingHandler implements JobHandler {

        private volatile CountDownLatch started = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        @Override
        public String jobType() {
            return "TEST_SLOW";
        }

        @Override
        public void handle(Job job) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("慢处理器被中断", e);
            }
        }

        boolean started() {
            return started.getCount() == 0;
        }

        void reset() {
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void release() {
            release.countDown();
        }
    }
}
