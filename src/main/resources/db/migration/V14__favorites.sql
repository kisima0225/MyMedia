-- ============================================================
-- 收藏。与播放/阅读进度一样是纯用户态：独立成表，绝不塞进媒体表。
-- 这是多用户设计的核心（spec §6.5）。
-- ============================================================

CREATE TABLE video_favorite (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    video_item_id BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_item_id)
);

-- 「我的收藏」按加入时间倒序，这个索引是它的主查询路径
CREATE INDEX idx_video_favorite_user_time ON video_favorite (user_id, created_at DESC);

-- image_favorite 允许收藏任意节点，包括纯目录（spec §6.5 明写）
CREATE TABLE image_favorite (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    image_node_id BIGINT      NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, image_node_id)
);

CREATE INDEX idx_image_favorite_user_time ON image_favorite (user_id, created_at DESC);
