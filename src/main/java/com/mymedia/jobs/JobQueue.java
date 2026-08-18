package com.mymedia.jobs;

import com.mymedia.shared.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobQueue {

    private final JobRepository repository;
    private final JdbcTemplate jdbc;

    JobQueue(JobRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /**
     * 入队一个任务。若 dedupKey 非空且已存在同 key 的未完成任务，
     * 直接返回既有任务的 id，不新建。
     */
    @Transactional
    public Long enqueue(String type, String payloadJson, String dedupKey) {
        String payload = payloadJson == null ? "{}" : payloadJson;
        return jdbc.queryForObject("""
                INSERT INTO job (type, payload, dedup_key)
                VALUES (?, CAST(? AS jsonb), ?)
                ON CONFLICT (dedup_key)
                    WHERE dedup_key IS NOT NULL AND status IN ('PENDING', 'RUNNING')
                DO UPDATE SET dedup_key = EXCLUDED.dedup_key
                RETURNING id
                """, Long.class, type, payload, dedupKey);
    }

    @Transactional(readOnly = true)
    public Job findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
    }

    @Transactional
    void markSucceeded(Long id) {
        Job job = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
        job.markSucceeded();
    }
}
