package com.mymedia.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "job")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "dedup_key", length = 128)
    private String dedupKey;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Job() {
        // JPA 要求的无参构造器
    }

    Job(String type, String payload, String dedupKey) {
        this.type = type;
        this.payload = payload;
        this.dedupKey = dedupKey;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public int getPriority() { return priority; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLastError() { return lastError; }
    public String getDedupKey() { return dedupKey; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }

    void markRunning(String owner, Instant leaseExpiry) {
        this.status = JobStatus.RUNNING;
        this.leaseOwner = owner;
        this.leaseExpiresAt = leaseExpiry;
        this.startedAt = Instant.now();
        this.attempts = this.attempts + 1;
    }

    void markSucceeded() {
        this.status = JobStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    /**
     * 失败后决定重试还是放弃。未达最大尝试次数则退回 PENDING 并按指数退避
     * 推迟下次调度时间；否则终结为 FAILED 并保留错误信息供排查。
     */
    void markFailed(String error, Instant nextAttemptAt) {
        this.lastError = error;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        if (this.attempts >= this.maxAttempts) {
            this.status = JobStatus.FAILED;
            this.finishedAt = Instant.now();
        } else {
            this.status = JobStatus.PENDING;
            this.scheduledAt = nextAttemptAt;
        }
    }
}
