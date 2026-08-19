package com.mymedia.video;

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

@Entity
@Table(name = "video_item")
public class VideoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    /** 恒为 "VIDEO"。复合外键把它钉死在所属库的 domain 上，见 ADR-001。 */
    @Column(nullable = false, length = 8, updatable = false)
    private String domain = "VIDEO";

    @Column(name = "folder_id")
    private Long folderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 16)
    private VideoItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private VideoStructure structure = VideoStructure.FLAT;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "sort_title", nullable = false)
    private String sortTitle;

    @Column
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VideoItem() {
        // JPA 要求的无参构造器
    }

    VideoItem(Long libraryId, VideoItemType itemType, VideoStructure structure, String title) {
        this.libraryId = libraryId;
        this.itemType = itemType;
        this.structure = structure;
        this.title = title;
        this.sortTitle = NaturalSortKey.of(title);
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getFolderId() { return folderId; }
    public VideoItemType getItemType() { return itemType; }
    public VideoStructure getStructure() { return structure; }
    public String getTitle() { return title; }
    public String getOriginalTitle() { return originalTitle; }
    public String getSortTitle() { return sortTitle; }
    public String getSummary() { return summary; }

    void assignFolder(Long folderId) {
        this.folderId = folderId;
    }

    /** 首次发现分组文件时把 FLAT 提升为 GROUPED。 */
    void promoteToGrouped() {
        this.structure = VideoStructure.GROUPED;
    }

    void rename(String newTitle) {
        this.title = newTitle;
        this.sortTitle = NaturalSortKey.of(newTitle);
    }
}
