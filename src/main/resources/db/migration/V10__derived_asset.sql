-- ============================================================
-- 派生资源：封面、缩略图、雪碧图、雪碧图 VTT。
-- 全部是「生成物」而非扫描所得，因此与 scanned_file 分表存放，
-- 目录独立于媒体库根路径，删光后可全量重建。详见 spec 6.2。
-- ============================================================

CREATE TABLE derived_asset (
    id                     BIGSERIAL PRIMARY KEY,
    kind                   VARCHAR(16) NOT NULL,
    -- 所有派生资源都从某一个原始文件生成（视频抽帧 / 漫画首页 / 图集首图），
    -- 因此这是单一外键，没有多态。
    source_scanned_file_id BIGINT      NOT NULL REFERENCES scanned_file (id) ON DELETE CASCADE,
    -- 相对于派生资源根目录（mymedia.preview.root），不含根路径本身
    relative_path          TEXT        NOT NULL,
    width                  INT,
    height                 INT,
    size_bytes             BIGINT      NOT NULL,
    generated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_derived_asset_kind CHECK (
        kind IN ('COVER', 'THUMBNAIL', 'SPRITE_SHEET', 'SPRITE_VTT'))
);

-- 一个来源文件的每种派生资源只有一份。重新生成是 UPDATE，不是再插一行。
ALTER TABLE derived_asset
    ADD CONSTRAINT uq_derived_asset_source_kind UNIQUE (source_scanned_file_id, kind);
ALTER TABLE derived_asset
    ADD CONSTRAINT uq_derived_asset_path UNIQUE (relative_path);

-- ------------------------------------------------------------
-- 现在才给各处的 cover_asset_id 补外键。
--
-- V6 / V8 建表时 derived_asset 还不存在（预览是 P8 的事），所以当时
-- 只有列没有约束。迁移的顺序本身就说明了模块的构建顺序。
--
-- ON DELETE SET NULL 是「派生目录删光后可全量重建」这句承诺的实现：
-- DELETE FROM derived_asset 之后所有封面引用自动置空，
-- 扫描完成时的补齐逻辑会把它们重新排队生成，用户数据一行不动。
-- ------------------------------------------------------------
ALTER TABLE video_item ADD CONSTRAINT fk_video_item_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE video_group ADD CONSTRAINT fk_video_group_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE collection ADD CONSTRAINT fk_collection_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;
ALTER TABLE image_node ADD CONSTRAINT fk_image_node_cover
    FOREIGN KEY (cover_asset_id) REFERENCES derived_asset (id) ON DELETE SET NULL;

-- 补齐逻辑按「还没有封面」筛选，部分索引让这个查询不必全表扫描
CREATE INDEX idx_video_item_without_cover ON video_item (library_id)
    WHERE cover_asset_id IS NULL;
CREATE INDEX idx_image_node_without_cover ON image_node (library_id)
    WHERE cover_asset_id IS NULL;