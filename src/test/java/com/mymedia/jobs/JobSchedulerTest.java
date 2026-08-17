package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(JobSchedulerTest.TestHandlers.class)
class JobSchedulerTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue jobQueue;

    @Autowired
    JobScheduler scheduler;

    @Autowired
    RecordingHandler recordingHandler;

    @Autowired
    AlwaysFailingHandler failingHandler;

    @Test
    void dispatchesJobToMatchingHandler() {
        Long id = jobQueue.enqueue("TEST_RECORD", "{\"v\":42}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(recordingHandler.handled()).contains(id));
        assertThat(jobQueue.findById(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
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
}
