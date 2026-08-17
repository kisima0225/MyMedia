package com.mymedia.jobs;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobQueue {

    private final JobRepository repository;

    JobQueue(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * 入队一个任务。若 dedupKey 非空且已存在同 key 的未完成任务，
     * 直接返回既有任务的 id，不新建。
     */
    @Transactional
    public Long enqueue(String type, String payloadJson, String dedupKey) {
        if (dedupKey != null) {
            var existing = repository.findActiveByDedupKey(dedupKey);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
        }
        String payload = payloadJson == null ? "{}" : payloadJson;
        return repository.saveAndFlush(new Job(type, payload, dedupKey)).getId();
    }

    @Transactional(readOnly = true)
    public Job findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
    }

    @Transactional
    public void markSucceeded(Long id) {
        Job job = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
        job.markSucceeded();
    }
}
