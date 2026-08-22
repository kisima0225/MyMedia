-- ============================================================
-- 图片域。与视频域刻意不对称：
--   视频语义强 —— 「一部电影」「一季」是刮削与播放的天然单位，用语义模型；
--   图片组织高度个人化 —— 画师/年份/合集、作者/系列/单行本/卷、来源/主题，
--   深度各不相同，因此用任意深度的自由树。详见 spec 6.4。
--
-- 核心设计：「书」与「文件夹」不是互斥的节点类型，而是同一节点的两种能力。
-- ============================================================

CREATE TABLE image_node (
    id                      BIGSERIAL PRIMARY KEY,
    library_id              BIGINT      NOT NULL,
    domain                  VARCHAR(8)  NOT NULL DEFAULT 'IMAGE',
    parent_id               BIGINT      REFERENCES image_node (id) ON DELETE CASCADE,

    -- 结构路径：'/1/17/93/'，由 id 组成。子树查询走前缀索引，面包屑直接解析。
    materialized_path       TEXT        NOT NULL,
    -- 顺序路径：'/1:1:1画师a/1:1:2卷/'，由各级 sort_key 组成。
    -- 存在的唯一理由是「强制书模式」要按目录深度优先顺序展开整棵子树的页，
    -- 而 id 路径的顺序是创建顺序，与名字顺序无关。
    sort_path               TEXT        NOT NULL,
    depth                   INT         NOT NULL,

    name                    TEXT        NOT NULL,
    sort_key                TEXT        NOT NULL,

    source_kind             VARCHAR(16) NOT NULL,
    -- ARCHIVE 节点指向压缩包本体（CBZ/ZIP），DIRECTORY 节点必须为空
    archive_scanned_file_id BIGINT      REFERENCES scanned_file (id) ON DELETE CASCADE,

    reading_mode            VARCHAR(16) NOT NULL DEFAULT 'AUTO',

    -- 计数字段。扫描结束时批量重算，不做实时递归统计。
    direct_page_count       INT         NOT NULL DEFAULT 0,
    child_node_count        INT         NOT NULL DEFAULT 0,
    total_page_count        INT         NOT NULL DEFAULT 0,

    cover_asset_id          BIGINT,
    title                   TEXT,
    summary                 TEXT,
    metadata                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    raw_metadata            JSONB,
    field_sources           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    locked_fields           TEXT[]      NOT NULL DEFAULT '{}',
    scrape_status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    scrape_source           VARCHAR(32),
    scrape_source_id        VARCHAR(64),

    status                  VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_image_node_source_kind CHECK (source_kind IN ('DIRECTORY', 'ARCHIVE')),
    CONSTRAINT ck_image_node_reading_mode CHECK (
        reading_mode IN ('AUTO', 'FORCE_BOOK', 'FORCE_FOLDER')),
    CONSTRAINT ck_image_node_status CHECK (status IN ('ACTIVE', 'MISSING')),
    CONSTRAINT ck_image_node_scrape_status CHECK (scrape_status IN (
        'NOT_APPLICABLE', 'PENDING', 'MATCHED', 'NO_MATCH', 'NEEDS_REVIEW', 'ERROR')),

    -- ARCHIVE 必须有压缩包本体，DIRECTORY 必须没有。等号两边都是布尔值，
    -- 一条 CHECK 同时表达了两个方向。
    CONSTRAINT ck_image_node_archive_ref CHECK (
        (source_kind = 'ARCHIVE') = (archive_scanned_file_id IS NOT NULL)),

    -- 域分区的数据库级强制，见 ADR-001
    CONSTRAINT ck_image_node_is_image CHECK (domain = 'IMAGE'),
    CONSTRAINT fk_image_node_library_domain
        FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain) ON DELETE CASCADE
);

-- 同一父节点下不允许重名。
-- NULLS NOT DISTINCT 是 PostgreSQL 15+ 的能力：默认情况下 NULL 互不相等，
-- 根节点（parent_id IS NULL）之间的重名根本不会被拦住。没有这个修饰词，
-- 顶层目录可以无限重复插入，find-or-create 每次扫描都会造一棵新树。
ALTER TABLE image_node
    ADD CONSTRAINT uq_image_node_sibling
        UNIQUE NULLS NOT DISTINCT (library_id, parent_id, name);

-- text_pattern_ops 让 LIKE '前缀%' 能走索引（默认排序规则下不行）
CREATE INDEX idx_image_node_subtree
    ON image_node (library_id, materialized_path text_pattern_ops);
CREATE INDEX idx_image_node_sortpath
    ON image_node (library_id, sort_path text_pattern_ops);
CREATE INDEX idx_image_node_parent ON image_node (parent_id, sort_key);
CREATE INDEX idx_image_node_archive ON image_node (archive_scanned_file_id);
-- 中文搜索主路径，见 spec 7.7
CREATE INDEX idx_image_node_name_trgm ON image_node USING gin (name gin_trgm_ops);

-- 语义层。页不建树节点 —— 一本 500 页的漫画若每页一个节点，树会被撑爆。
CREATE TABLE image_file (
    id                 BIGSERIAL PRIMARY KEY,
    scanned_file_id    BIGINT  NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    node_id            BIGINT  NOT NULL REFERENCES image_node (id) ON DELETE CASCADE,

    -- 展示用页码。扫描结束时用窗口函数一条 SQL 重编号，见 Task 5。
    page_index         INT     NOT NULL DEFAULT 0,
    -- 排序真值。页码是它的产物，不是相反。
    sort_key           TEXT    NOT NULL,

    -- 非空表示来自压缩包内条目；为空表示散图目录里的独立文件
    archive_entry_name TEXT,

    width              INT,
    height             INT,
    format             VARCHAR(16),
    is_animated        BOOLEAN NOT NULL DEFAULT FALSE
);

-- 散图：一个 scanned_file 一行（entry 为 NULL）；CBZ：一个 scanned_file N 行。
-- 同样需要 NULLS NOT DISTINCT，否则散图可以被重复登记任意多次。
ALTER TABLE image_file
    ADD CONSTRAINT uq_image_file_entry
        UNIQUE NULLS NOT DISTINCT (scanned_file_id, archive_entry_name);

CREATE INDEX idx_image_file_node ON image_file (node_id, page_index);
CREATE INDEX idx_image_file_node_sort ON image_file (node_id, sort_key);