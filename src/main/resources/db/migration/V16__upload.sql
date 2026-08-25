-- ============================================================
-- 分片上传（spec §7.6）。
--
-- 会话与分片是两张表：分片的到达是高频、幂等、可乱序的写入，
-- 而会话是低频的状态机。塞进一张表就意味着每收一片都要改会话行，
-- 在并发上传多片时互相争锁。
-- ============================================================

CREATE TABLE upload_session (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_library_id BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    -- 已经过 SafeFileName 净化，绝不是客户端原样送来的串
    filename          TEXT        NOT NULL,
    -- 合并落库后在媒体库里的相对路径；秒传与未完成时为 NULL
    relative_path     TEXT,
    total_size        BIGINT      NOT NULL,
    -- 分片大小由服务端决定并下发，客户端不许自选：
    -- 分片边界一旦由两边各自计算，断点续传的「第 N 片」就没有共同含义了
    chunk_size        INT         NOT NULL,
    total_chunks      INT         NOT NULL,
    -- 客户端声明的采样哈希，合并后据此校验
    content_hash      VARCHAR(64) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'RECEIVING',
    -- 秒传命中：没有任何字节真的上传过
    instant           BOOLEAN     NOT NULL DEFAULT false,
    -- 秒传命中的既有物理文件。合并入库的那条要等扫描建行，所以那种情况留空
    scanned_file_id   BIGINT      REFERENCES scanned_file (id) ON DELETE SET NULL,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT ck_upload_session_status CHECK (
        status IN ('RECEIVING', 'ASSEMBLING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_upload_session_sizes CHECK (
        total_size > 0 AND chunk_size > 0 AND total_chunks > 0)
);

CREATE INDEX idx_upload_session_user ON upload_session (user_id, created_at DESC);

CREATE TABLE upload_chunk (
    session_id  BIGINT      NOT NULL REFERENCES upload_session (id) ON DELETE CASCADE,
    chunk_index INT         NOT NULL,
    size        BIGINT      NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 复合主键让「同一片重传」变成一次 ON CONFLICT，天然幂等
    PRIMARY KEY (session_id, chunk_index)
);
