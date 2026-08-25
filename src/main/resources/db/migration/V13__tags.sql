-- ============================================================
-- 标签。视频标签与图片标签互不混用（spec §6.2），
-- 而这条不变式和 video_item / collection 一样由数据库强制，
-- 用的是同一个复合外键手法，见 ADR-001。
-- ============================================================

CREATE TABLE tag (
    id         BIGSERIAL PRIMARY KEY,
    domain     VARCHAR(8)  NOT NULL,
    name       TEXT        NOT NULL,
    -- 小写化、空白折叠、去标点后的形式，只用来做唯一键；中文保留原字
    slug       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tag_domain CHECK (domain IN ('VIDEO', 'IMAGE'))
);

-- 「科幻」与「科幻！」是同一个标签；视频的「科幻」与图片的「科幻」不是
ALTER TABLE tag ADD CONSTRAINT uq_tag_domain_slug UNIQUE (domain, slug);

-- 看似冗余的唯一键，是让关联表能用复合外键把 domain 钉死的前提
ALTER TABLE tag ADD CONSTRAINT uq_tag_id_domain UNIQUE (id, domain);

CREATE INDEX idx_tag_domain_name ON tag (domain, name);

CREATE TABLE video_item_tag (
    video_item_id BIGINT     NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    tag_id        BIGINT     NOT NULL,
    domain        VARCHAR(8) NOT NULL DEFAULT 'VIDEO',
    PRIMARY KEY (video_item_id, tag_id),
    CONSTRAINT ck_video_item_tag_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_video_item_tag_domain
        FOREIGN KEY (tag_id, domain) REFERENCES tag (id, domain) ON DELETE CASCADE
);

-- 按标签列条目走这个索引（主键是 (item, tag)，反向查需要它）
CREATE INDEX idx_video_item_tag_tag ON video_item_tag (tag_id);

CREATE TABLE image_node_tag (
    image_node_id BIGINT     NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,
    tag_id        BIGINT     NOT NULL,
    domain        VARCHAR(8) NOT NULL DEFAULT 'IMAGE',
    PRIMARY KEY (image_node_id, tag_id),
    CONSTRAINT ck_image_node_tag_is_image CHECK (domain = 'IMAGE'),
    CONSTRAINT fk_image_node_tag_domain
        FOREIGN KEY (tag_id, domain) REFERENCES tag (id, domain) ON DELETE CASCADE
);

CREATE INDEX idx_image_node_tag_tag ON image_node_tag (tag_id);
