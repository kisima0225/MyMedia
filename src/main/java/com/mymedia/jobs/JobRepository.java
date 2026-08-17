package com.mymedia.jobs;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface JobRepository extends JpaRepository<Job, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.id = :id")
    Optional<Job> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT j FROM Job j
            WHERE j.dedupKey = :dedupKey AND j.status IN (
                com.mymedia.jobs.JobStatus.PENDING, com.mymedia.jobs.JobStatus.RUNNING)
            """)
    Optional<Job> findActiveByDedupKey(@Param("dedupKey") String dedupKey);

    /**
     * FOR UPDATE SKIP LOCKED 是 PostgreSQL 的行级抢占原语：
     * 多个 worker 并发执行这条查询时，各自跳过已被他人锁住的行，
     * 因而拿到互不相交的任务集合，且互不阻塞。
     * 这是不引入消息队列却能安全并发消费的关键，见 ADR-003。
     */
    @Query(value = """
            SELECT * FROM job
            WHERE status = 'PENDING' AND scheduled_at <= :now
            ORDER BY priority DESC, scheduled_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> claimBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * 回收租约已过期的任务：worker 进程崩溃后，它持有的任务会永远停在
     * RUNNING。租约到期即视为 worker 已死，任务退回 PENDING 供他人重新抢占。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE job
            SET status = 'PENDING', lease_owner = NULL, lease_expires_at = NULL
            WHERE status = 'RUNNING' AND lease_expires_at < :now
            """, nativeQuery = true)
    int reclaimExpiredLeases(@Param("now") Instant now);
}
