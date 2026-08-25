package com.mymedia.upload;

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
@Table(name = "upload_session")
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "target_library_id", nullable = false, updatable = false)
    private Long targetLibraryId;

    @Column(nullable = false, updatable = false)
    private String filename;

    @Column(name = "relative_path")
    private String relativePath;

    @Column(name = "total_size", nullable = false, updatable = false)
    private long totalSize;

    @Column(name = "chunk_size", nullable = false, updatable = false)
    private int chunkSize;

    @Column(name = "total_chunks", nullable = false, updatable = false)
    private int totalChunks;

    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UploadStatus status = UploadStatus.RECEIVING;

    @Column(nullable = false)
    private boolean instant;

    @Column(name = "scanned_file_id")
    private Long scannedFileId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UploadSession() {
        // JPA 要求的无参构造器
    }

    UploadSession(Long userId, Long targetLibraryId, String filename, long totalSize,
                  int chunkSize, int totalChunks, String contentHash) {
        this.userId = userId;
        this.targetLibraryId = targetLibraryId;
        this.filename = filename;
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.contentHash = contentHash;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTargetLibraryId() { return targetLibraryId; }
    public String getFilename() { return filename; }
    public String getRelativePath() { return relativePath; }
    public long getTotalSize() { return totalSize; }
    public int getChunkSize() { return chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public String getContentHash() { return contentHash; }
    public UploadStatus getStatus() { return status; }
    public boolean isInstant() { return instant; }
    public Long getScannedFileId() { return scannedFileId; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    // 注意这里<b>没有</b> markAssembling()：转 ASSEMBLING 必须是一条
    // 「判断与写入压成一句」的条件 UPDATE（Task 12 的
    // UploadSessionRepository.markAssemblingIfReceiving），否则两片同时到齐时
    // 会入队两次合并任务。留一个实体方法在这里只会诱人去用错的那条路。

    /** 秒传命中：一个字节都没传，直接指向那个既有文件。 */
    void completeInstantly(Long existingScannedFileId) {
        this.instant = true;
        this.scannedFileId = existingScannedFileId;
        this.status = UploadStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    void completeAt(String relativePath) {
        this.relativePath = relativePath;
        this.status = UploadStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    void fail(String reason) {
        this.status = UploadStatus.FAILED;
        this.lastError = reason;
        this.completedAt = Instant.now();
    }
}
