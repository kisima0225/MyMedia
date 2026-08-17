CREATE TABLE job (
    id               BIGSERIAL PRIMARY KEY,
    type             VARCHAR(48)  NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    priority         INT          NOT NULL DEFAULT 0,
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    last_error       TEXT,
    dedup_key        VARCHAR(128),
    scheduled_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    lease_owner      VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

-- 去重只对「未完成」的任务生效：同一个库不应被同时排入两次扫描，
-- 但上一次扫描完成后必须能再次排入。部分唯一索引正好表达这个语义。
CREATE UNIQUE INDEX uq_job_dedup_active
    ON job (dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING', 'RUNNING');

-- 抢占查询的支撑索引：status + scheduled_at 是 WHERE 与 ORDER BY 的组合
CREATE INDEX idx_job_claim ON job (status, scheduled_at) WHERE status = 'PENDING';

-- 租约回收查询的支撑索引
CREATE INDEX idx_job_lease ON job (lease_expires_at) WHERE status = 'RUNNING';
