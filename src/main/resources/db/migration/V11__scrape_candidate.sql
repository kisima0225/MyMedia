-- ============================================================
-- 刮削候选：scrape_status = NEEDS_REVIEW 时的待确认列表。
--
-- 与 share_link 一致，用两个可空外键 + CHECK 恰有一个非空，
-- 而不是 (target_type, target_id) 多态列：多态外键在 PostgreSQL 里
-- 建不了引用完整性约束，删掉条目会留下悬空候选。详见 spec 6.6。
-- ============================================================

CREATE TABLE scrape_candidate (
    id            BIGSERIAL PRIMARY KEY,
    video_item_id BIGINT      REFERENCES video_item (id) ON DELETE CASCADE,
    image_node_id BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,
    provider      VARCHAR(32) NOT NULL,
    external_id   VARCHAR(64),
    title         TEXT,
    year          INT,
    -- 0.000–1.000，来自 TitleSimilarity 的二元组 Dice 系数
    score         NUMERIC(4,3) NOT NULL,
    -- 搜索结果原样存下来，用户确认时不必再查一次
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scrape_candidate_target CHECK (
        num_nonnulls(video_item_id, image_node_id) = 1)
);

CREATE INDEX idx_scrape_candidate_video ON scrape_candidate (video_item_id, score DESC);
CREATE INDEX idx_scrape_candidate_image ON scrape_candidate (image_node_id, score DESC);

-- ------------------------------------------------------------
-- 合集按 (库, 名字) find-or-create，需要这个唯一键才能用 ON CONFLICT。
-- 计划 03 的 V6 建 collection 表时还没有写入方，所以没建它。
-- ------------------------------------------------------------
ALTER TABLE collection ADD CONSTRAINT uq_collection_library_name UNIQUE (library_id, name);
