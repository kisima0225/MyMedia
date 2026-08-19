-- ============================================================
-- 视频域语义层。图片域走 image_node / image_file，两者刻意不对称：
-- 视频语义强（一部电影、一季是刮削与播放的天然单位），
-- 图片组织高度个人化，需要任意深度的自由树。详见 spec 6.4。
-- ============================================================

-- 目录树浏览视图（派生索引，非主模型）。
-- 视频域的主浏览方式是语义化的；本表只承载导航，不承载元数据与进度。
CREATE TABLE video_folder (
    id                BIGSERIAL PRIMARY KEY,
    library_id        BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    parent_id         BIGINT      REFERENCES video_folder (id) ON DELETE CASCADE,
    materialized_path TEXT        NOT NULL,
    depth             INT         NOT NULL,
    name              TEXT        NOT NULL,
    sort_key          TEXT        NOT NULL,
    direct_item_count INT         NOT NULL DEFAULT 0,
    total_item_count  INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE video_folder
    ADD CONSTRAINT uq_video_folder_path UNIQUE (library_id, materialized_path);

-- text_pattern_ops 让 LIKE '前缀%' 能走索引（默认的排序规则下不行）
CREATE INDEX idx_video_folder_subtree
    ON video_folder (library_id, materialized_path text_pattern_ops);
CREATE INDEX idx_video_folder_parent ON video_folder (parent_id, sort_key);

-- 一个"作品"：一部电影 / 一部番 / 一个系列
CREATE TABLE video_item (
    id             BIGSERIAL PRIMARY KEY,
    library_id     BIGINT      NOT NULL,
    domain         VARCHAR(8)   NOT NULL DEFAULT 'VIDEO',
    folder_id      BIGINT      REFERENCES video_folder (id) ON DELETE SET NULL,
    item_type      VARCHAR(16)  NOT NULL,
    structure      VARCHAR(8)   NOT NULL DEFAULT 'FLAT',
    title          TEXT         NOT NULL,
    original_title TEXT,
    sort_title     TEXT         NOT NULL,
    summary        TEXT,
    release_date   DATE,
    rating         NUMERIC(3,1),
    cover_asset_id BIGINT,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    raw_metadata   JSONB,
    field_sources  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    locked_fields  TEXT[]       NOT NULL DEFAULT '{}',
    scrape_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    scrape_source  VARCHAR(32),
    scrape_source_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_video_item_type CHECK (
        item_type IN ('MOVIE', 'SERIES', 'SINGLE_VIDEO', 'VIDEO_SERIES')),
    CONSTRAINT ck_video_item_structure CHECK (structure IN ('FLAT', 'GROUPED')),
    CONSTRAINT ck_video_item_scrape_status CHECK (scrape_status IN (
        'NOT_APPLICABLE', 'PENDING', 'MATCHED', 'NO_MATCH', 'NEEDS_REVIEW', 'ERROR')),
    -- 域分区的数据库级强制，见 ADR-001
    CONSTRAINT ck_video_item_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_video_item_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

CREATE INDEX idx_video_item_library ON video_item (library_id, sort_title);
CREATE INDEX idx_video_item_folder ON video_item (folder_id, sort_title);
-- 中文搜索主路径，见 spec 7.7
CREATE INDEX idx_video_item_title_trgm ON video_item USING gin (title gin_trgm_ops);

-- 可选分组：季 / 分册。仅 structure = 'GROUPED' 时存在。
CREATE TABLE video_group (
    id           BIGSERIAL PRIMARY KEY,
    item_id      BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    group_index  INT         NOT NULL,
    name         TEXT        NOT NULL,
    sort_key     TEXT        NOT NULL,
    summary      TEXT,
    cover_asset_id BIGINT,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_video_group_index UNIQUE (item_id, group_index)
);

-- 语义层。item_id 必填、group_id 可空 —— 外键单一，
-- 不需要"隐式分组"这类绕弯设计。
CREATE TABLE video_file (
    id               BIGSERIAL PRIMARY KEY,
    scanned_file_id  BIGINT      NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    item_id          BIGINT      NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    group_id         BIGINT      REFERENCES video_group (id) ON DELETE SET NULL,
    role             VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    episode_index    INT,
    sort_key         TEXT        NOT NULL DEFAULT '',
    duration_seconds INT,
    width            INT,
    height           INT,
    video_codec      VARCHAR(32),
    audio_codec      VARCHAR(32),
    bitrate          BIGINT,
    container        VARCHAR(16),
    probe_raw        JSONB,
    CONSTRAINT ck_video_file_role CHECK (
        role IN ('PRIMARY', 'VERSION', 'EXTRA', 'SUBTITLE', 'TRAILER'))
);

-- 一个物理文件只能对应一个语义条目
ALTER TABLE video_file ADD CONSTRAINT uq_video_file_scanned UNIQUE (scanned_file_id);
CREATE INDEX idx_video_file_item ON video_file (item_id, sort_key);
CREATE INDEX idx_video_file_group ON video_file (group_id, episode_index);

-- 跨条目聚合：一部电影可同时属于「指环王三部曲」与「托尔金改编作品」
CREATE TABLE collection (
    id             BIGSERIAL PRIMARY KEY,
    library_id     BIGINT      NOT NULL,
    domain         VARCHAR(8)  NOT NULL DEFAULT 'VIDEO',
    name           TEXT        NOT NULL,
    sort_key       TEXT        NOT NULL,
    summary        TEXT,
    cover_asset_id BIGINT,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_collection_is_video CHECK (domain = 'VIDEO'),
    CONSTRAINT fk_collection_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

CREATE TABLE collection_item (
    collection_id BIGINT NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    video_item_id BIGINT NOT NULL REFERENCES video_item (id) ON DELETE CASCADE,
    sort_order    INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (collection_id, video_item_id)
);
