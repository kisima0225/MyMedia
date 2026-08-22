package com.mymedia.image;

import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一页。
 *
 * <p>散图目录：每张图一个 {@code scanned_file}，{@code archiveEntryName} 为 null。
 * <br>CBZ：一个 {@code scanned_file} 对应 N 行，各带自己的条目名。
 *
 * <p><b>页不建树节点。</b>
 */
@Entity
@Table(name = "image_file")
public class ImageFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 指向物理层。文件改名或移动时只有 {@code scanned_file.relative_path} 变化，
     * 本表与用户阅读进度完全不受影响 —— spec §6.1 分层设计的收益。
     */
    @Column(name = "scanned_file_id", nullable = false, updatable = false)
    private Long scannedFileId;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "archive_entry_name")
    private String archiveEntryName;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(length = 16)
    private String format;

    @Column(name = "is_animated", nullable = false)
    private boolean animated;

    protected ImageFile() {
    }

    /** 散图目录里的一张图。 */
    ImageFile(Long scannedFileId, Long nodeId, String fileName) {
        this.scannedFileId = scannedFileId;
        this.nodeId = nodeId;
        this.sortKey = NaturalSortKey.of(fileName);
    }

    /** 压缩包里的一个条目。 */
    ImageFile(Long scannedFileId, Long nodeId, String entryName, int pageIndex) {
        this.scannedFileId = scannedFileId;
        this.nodeId = nodeId;
        this.archiveEntryName = entryName;
        this.sortKey = NaturalSortKey.of(entryName);
        this.pageIndex = pageIndex;
    }

    public Long getId() { return id; }
    public Long getScannedFileId() { return scannedFileId; }
    public Long getNodeId() { return nodeId; }
    public int getPageIndex() { return pageIndex; }
    public String getSortKey() { return sortKey; }
    public String getArchiveEntryName() { return archiveEntryName; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getFormat() { return format; }
    public boolean isAnimated() { return animated; }

    /** 文件被移动到了另一个目录，页跟着换节点。 */
    void reattachTo(Long nodeId) {
        this.nodeId = nodeId;
    }

    /** 由计划 05 的 preview 模块探测后回填。 */
    void applyDimensions(Integer width, Integer height, String format, boolean animated) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.animated = animated;
    }
}