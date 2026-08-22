-- 用户态数据独立成表，绝不塞进媒体表。这是多用户设计的核心：
-- 同一本漫画，每个用户读到哪一页互不干扰。
CREATE TABLE image_progress (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    image_node_id BIGINT      NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    page_index    INT         NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, image_node_id)
);

-- 「继续阅读」查询：按用户过滤、按时间倒序。
-- 这里没有 completed 列 —— 视频那边有，是因为时长要等 ffprobe 探测才知道，
-- 必须存快照；图片的总页数本来就在 image_node 上维护着，
-- 「读完没有」用一次连接就能算出来，存下来只会多一个会失效的冗余字段。
CREATE INDEX idx_image_progress_recent ON image_progress (user_id, updated_at DESC);
