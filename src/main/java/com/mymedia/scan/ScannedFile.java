package com.mymedia.scan;

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
@Table(name = "scanned_file")
public class ScannedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private Instant mtime;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(nullable = false, length = 16)
    private String extension;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ScannedFileStatus status = ScannedFileStatus.ACTIVE;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    protected ScannedFile() {
        // JPA 要求的无参构造器
    }

    ScannedFile(Long libraryId, String relativePath, long sizeBytes, Instant mtime, String extension) {
        this.libraryId = libraryId;
        this.relativePath = relativePath;
        this.sizeBytes = sizeBytes;
        this.mtime = mtime;
        this.extension = extension;
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public String getRelativePath() { return relativePath; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getMtime() { return mtime; }
    public String getContentHash() { return contentHash; }
    public String getExtension() { return extension; }
    public ScannedFileStatus getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    void touch(Instant seenAt) {
        this.lastSeenAt = seenAt;
        this.status = ScannedFileStatus.ACTIVE;
    }

    void updateContent(long sizeBytes, Instant mtime, Instant seenAt) {
        this.sizeBytes = sizeBytes;
        this.mtime = mtime;
        this.contentHash = null;      // 内容变了，旧哈希作废
        touch(seenAt);
    }

    void markMissing() {
        this.status = ScannedFileStatus.MISSING;
    }

    /**
     * 文件被改名或移动：只换路径，id 保持不变。
     * 语义层与用户进度通过外键引用 id，因此全部无损保留。
     */
    void relocateTo(String newRelativePath, Instant seenAt) {
        this.relativePath = newRelativePath;
        touch(seenAt);
    }

    void assignContentHash(String hash) {
        this.contentHash = hash;
    }
}
