-- ============================================================
-- 搜索的两条路径（spec 7.7）：
--   中文主路径 —— pg_trgm 三元组索引 + ILIKE 子串匹配
--   拉丁文路径 —— tsvector 生成列，提供词干化与相关度排序
-- 两条路径各管各的，查询时取并集、分层排序。见 ADR-006。
--
-- ⚠ 生成列里必须用双参数的 to_tsvector(regconfig, text)：
--   单参数版本是 STABLE（依赖 default_text_search_config 会话设置），
--   PostgreSQL 会拒绝，报 "generation expression is not immutable"。
-- ============================================================

ALTER TABLE video_item ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english',
        coalesce(title, '') || ' ' ||
        coalesce(original_title, '') || ' ' ||
        coalesce(summary, ''))) STORED;

CREATE INDEX idx_video_item_fts ON video_item USING gin (search_vector);

-- 计划 03 只给 title 建了三元组索引，原名同样要能搜
CREATE INDEX idx_video_item_original_trgm
    ON video_item USING gin (original_title gin_trgm_ops);

ALTER TABLE image_node ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english',
        coalesce(title, '') || ' ' ||
        coalesce(name, '') || ' ' ||
        coalesce(summary, ''))) STORED;

CREATE INDEX idx_image_node_fts ON image_node USING gin (search_vector);

-- 计划 04 只给 name 建了三元组索引；刮削回来的 title 也要能搜
CREATE INDEX idx_image_node_title_trgm
    ON image_node USING gin (title gin_trgm_ops);
