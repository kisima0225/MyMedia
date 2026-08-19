-- 用户态数据独立成表，绝不塞进媒体表。这是多用户设计的核心：
-- 同一部片子，每个用户有各自的进度，互不干扰。
CREATE TABLE video_progress (
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    video_file_id    BIGINT      NOT NULL REFERENCES video_file (id) ON DELETE CASCADE,
    position_seconds INT         NOT NULL,
    duration_seconds INT,
    completed        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_file_id)
);

-- 「继续观看」查询：按用户过滤、排除已看完、按时间倒序
CREATE INDEX idx_video_progress_continue
    ON video_progress (user_id, updated_at DESC)
    WHERE completed = FALSE;
