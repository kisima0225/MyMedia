package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue jobQueue;

    private String uniqueKey() {
        return "k" + UUID.randomUUID();
    }

    @Test
    void enqueuesJobInPendingState() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", uniqueKey());

        Job job = jobQueue.findById(id);
        assertThat(job.getType()).isEqualTo("LIBRARY_SCAN");
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getPayload()).contains("libraryId");
    }

    @Test
    void deduplicatesByKey() {
        String key = uniqueKey();

        Long first = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", key);
        Long second = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", key);

        // 同一个 dedupKey 不应产生第二个任务——防止同一个库被反复排入扫描
        assertThat(second).isEqualTo(first);
    }

    @Test
    void allowsSameKeyAfterPreviousCompleted() {
        String key = uniqueKey();
        Long first = jobQueue.enqueue("LIBRARY_SCAN", "{}", key);
        jobQueue.markSucceeded(first);

        Long second = jobQueue.enqueue("LIBRARY_SCAN", "{}", key);

        // 上一次已完成，同一个 key 应能再次入队
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void nullDedupKeyNeverDeduplicates() {
        Long first = jobQueue.enqueue("PREVIEW_GENERATE", "{\"fileId\":1}", null);
        Long second = jobQueue.enqueue("PREVIEW_GENERATE", "{\"fileId\":1}", null);

        assertThat(second).isNotEqualTo(first);
    }
}
