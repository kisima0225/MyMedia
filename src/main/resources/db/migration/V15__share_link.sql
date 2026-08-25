-- ============================================================
-- 分享链接。令牌本身就是凭证（bearer capability）：
-- 拿到令牌的人不需要账号也能看，因此令牌必须足够长且随机。
--
-- 目标用「两个可空外键 + CHECK 恰有一个非空」而非 (target_type, target_id)
-- 多态列（spec §6.2）：多态外键在 PostgreSQL 里建不了引用完整性约束，
-- 删掉条目就会留下指向虚空的分享链接。
-- ============================================================

CREATE TABLE share_link (
    id            BIGSERIAL PRIMARY KEY,
    -- 32 字节 SecureRandom 的 Base64URL 无填充形式，43 个字符
    token         VARCHAR(64) NOT NULL,
    library_id    BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    video_item_id BIGINT      REFERENCES video_item (id) ON DELETE CASCADE,
    image_node_id BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,
    -- 可空：不设密码的链接是常态。存的是 {bcrypt} 前缀的委派编码
    password_hash TEXT,
    -- 可空：NULL 表示永不过期
    expires_at    TIMESTAMPTZ,
    created_by    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 撤销是打标记而不是删行：用户要能在列表里看见「这条我撤了」
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT ck_share_link_single_target
        CHECK (num_nonnulls(video_item_id, image_node_id) = 1)
);

ALTER TABLE share_link ADD CONSTRAINT uq_share_link_token UNIQUE (token);

-- 「我创建的分享」按时间倒序，这是管理页的主查询路径
CREATE INDEX idx_share_link_creator ON share_link (created_by, created_at DESC);
