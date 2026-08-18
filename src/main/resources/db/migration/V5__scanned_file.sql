-- 物理层：只记录「磁盘上有一个文件」，不含任何领域语义。
-- 视频域与图片域的语义表通过外键引用本表，因此文件改名或移动时
-- 只需更新 relative_path 一列，语义层与用户进度自动跟随，无损保留。
CREATE TABLE scanned_file (
    id            BIGSERIAL PRIMARY KEY,
    library_id    BIGINT      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    relative_path TEXT        NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    mtime         TIMESTAMPTZ NOT NULL,
    content_hash  VARCHAR(64),
    extension     VARCHAR(16) NOT NULL,
    mime_type     VARCHAR(128),
    status        VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scanned_file_status CHECK (status IN ('ACTIVE', 'MISSING'))
);

-- 同一个库内路径唯一；不同库可以有相同的相对路径
ALTER TABLE scanned_file
    ADD CONSTRAINT uq_scanned_file_path UNIQUE (library_id, relative_path);

-- 对账时按库全量拉取当前记录，这个索引是主查询路径
CREATE INDEX idx_scanned_file_library_status ON scanned_file (library_id, status);

-- 改名检测按内容哈希配对「消失」与「新增」，只在有哈希的行上建索引
CREATE INDEX idx_scanned_file_hash ON scanned_file (library_id, content_hash)
    WHERE content_hash IS NOT NULL;
