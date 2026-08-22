package com.mymedia.image;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 图片库的树节点。
 *
 * <p><b>「书」与「文件夹」不是互斥类型，而是同一节点的两种能力</b>——
 * 一个目录既有散图又有子目录时，两个入口同时提供。这是 spec §6.4 的核心设计，
 * 也是与 Perfect Viewer 一致的交互模型。
 *
 * <p>节点带两条路径：{@code materializedPath} 由 id 组成，负责结构
 * （子树查询、面包屑）；{@code sortPath} 由各级排序键组成，负责顺序
 * （强制书模式下按目录顺序展开整棵子树的页）。移动子树时两条一起重写。
 */
@Entity
@Table(name = "image_node")
public class ImageNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    /** 恒为 "IMAGE"。复合外键把它钉死在所属库的 domain 上，见 ADR-001。 */
    @Column(nullable = false, length = 8, updatable = false)
    private String domain = "IMAGE";

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "materialized_path", nullable = false)
    private String materializedPath;

    @Column(name = "sort_path", nullable = false)
    private String sortPath;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 16)
    private ImageSourceKind sourceKind;

    @Column(name = "archive_scanned_file_id")
    private Long archiveScannedFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reading_mode", nullable = false, length = 16)
    private ImageReadingMode readingMode = ImageReadingMode.AUTO;

    @Column(name = "direct_page_count", nullable = false)
    private int directPageCount;

    @Column(name = "child_node_count", nullable = false)
    private int childNodeCount;

    @Column(name = "total_page_count", nullable = false)
    private int totalPageCount;

    /**
     * 封面派生资源 id。**只读映射**：这一列由计划 05 的 preview 模块用一条
     * {@code UPDATE ... WHERE cover_asset_id IS NULL} 原子写入，本实体只负责读出来展示。
     * 做成可写会让一个在内存里待了一会儿的陈旧实体在下次 flush 时把刚生成好的封面刷回 null。
     */
    @Column(name = "cover_asset_id", insertable = false, updatable = false)
    private Long coverAssetId;

    /** 刮削或用户编辑得到的标题。为空时展示 {@link #name}。 */
    @Column
    private String title;

    @Column
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ImageNodeStatus status = ImageNodeStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ImageNode() {
        // JPA 要求的无参构造器
    }

    private ImageNode(Long libraryId, Long parentId, String parentPath, String parentSortPath,
                      String name, ImageSourceKind sourceKind, Long archiveScannedFileId) {
        this.libraryId = libraryId;
        this.parentId = parentId;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);
        this.sourceKind = sourceKind;
        this.archiveScannedFileId = archiveScannedFileId;
        // 路径含自身 id，插入拿到 id 之后才能确定，先占位为父路径
        this.materializedPath = parentPath;
        this.sortPath = parentSortPath;
        this.depth = MaterializedPath.depthOf(parentPath);
    }

    static ImageNode directory(Long libraryId, Long parentId,
                               String parentPath, String parentSortPath, String name) {
        return new ImageNode(libraryId, parentId, parentPath, parentSortPath,
                name, ImageSourceKind.DIRECTORY, null);
    }

    static ImageNode archive(Long libraryId, Long parentId,
                             String parentPath, String parentSortPath,
                             String name, Long archiveScannedFileId) {
        return new ImageNode(libraryId, parentId, parentPath, parentSortPath,
                name, ImageSourceKind.ARCHIVE, archiveScannedFileId);
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getParentId() { return parentId; }
    public String getMaterializedPath() { return materializedPath; }
    public String getSortPath() { return sortPath; }
    public int getDepth() { return depth; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
    public String getTitle() { return title; }
    public ImageSourceKind getSourceKind() { return sourceKind; }
    public Long getArchiveScannedFileId() { return archiveScannedFileId; }
    public ImageReadingMode getReadingMode() { return readingMode; }
    public int getDirectPageCount() { return directPageCount; }
    public int getChildNodeCount() { return childNodeCount; }
    public int getTotalPageCount() { return totalPageCount; }
    public Long getCoverAssetId() { return coverAssetId; }
    public ImageNodeStatus getStatus() { return status; }

    /** 刮削到标题就用标题，否则回落到目录名——没有刮削也必须完全可用。 */
    public String getDisplayName() {
        return title == null || title.isBlank() ? name : title;
    }

    /**
     * 能否进入阅读器。
     *
     * <p>{@code FORCE_BOOK} 下恒为真：用户已经明确说了「这是一本书」，
     * 哪怕直属图片为零（页全在子目录里）也要给阅读入口。
     */
    public boolean isReadable() {
        return switch (readingMode) {
            case FORCE_BOOK -> true;
            case FORCE_FOLDER -> false;
            case AUTO -> directPageCount > 0;
        };
    }

    /** 能否进入子项网格。 */
    public boolean isBrowsable() {
        return switch (readingMode) {
            case FORCE_BOOK -> false;
            case FORCE_FOLDER -> true;
            case AUTO -> childNodeCount > 0;
        };
    }

    /** 插入拿到 id 之后补全两条路径。 */
    void finalizePaths(String parentPath, String parentSortPath) {
        this.materializedPath = MaterializedPath.childOf(parentPath, this.id);
        this.sortPath = parentSortPath + this.sortKey + "/";
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    /** 目录改名：结构路径不变（由 id 组成），顺序路径要跟着变。 */
    void rename(String newName, String parentSortPath) {
        this.name = newName;
        this.sortKey = NaturalSortKey.of(newName);
        this.sortPath = parentSortPath + this.sortKey + "/";
    }

    /** 目录移动到新父节点。子树的路径重写由 {@code ImageTreeRelocator} 一条 SQL 完成。 */
    void moveTo(Long newParentId, String newParentPath, String newParentSortPath) {
        this.parentId = newParentId;
        this.materializedPath = MaterializedPath.childOf(newParentPath, this.id);
        this.sortPath = newParentSortPath + this.sortKey + "/";
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    void overrideReadingMode(ImageReadingMode mode) {
        this.readingMode = mode;
    }

    void setCounts(int direct, int child, int total) {
        this.directPageCount = direct;
        this.childNodeCount = child;
        this.totalPageCount = total;
    }
}