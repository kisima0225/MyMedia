CREATE TABLE libraries (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    domain             VARCHAR(8)   NOT NULL,
    root_path          TEXT         NOT NULL UNIQUE,
    scan_cron          VARCHAR(64),
    metadata_providers TEXT[]       NOT NULL DEFAULT '{}',
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_libraries_domain CHECK (domain IN ('VIDEO', 'IMAGE'))
);

-- 关键：这个看似冗余的唯一键，是让子表能用复合外键把自己的 domain
-- 钉死在所属库的 domain 上的前提。CHECK 约束无法跨表引用，
-- 复合外键是 PostgreSQL 中声明式强制跨表不变式的标准手法。
-- 效果：视频条目在数据库层面就不可能落进图片库。详见 spec 5.1。
ALTER TABLE libraries ADD CONSTRAINT uq_library_domain UNIQUE (id, domain);

CREATE TABLE library_access (
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    library_id BIGINT NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

CREATE INDEX idx_library_access_user ON library_access (user_id);
