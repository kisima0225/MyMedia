package com.mymedia.preview;

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
@Table(name = "derived_asset")
public class DerivedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DerivedAssetKind kind;

    @Column(name = "source_scanned_file_id", nullable = false)
    private Long sourceScannedFileId;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    private Integer width;

    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    protected DerivedAsset() {
        // JPA 要求的无参构造器
    }

    DerivedAsset(DerivedAssetKind kind, Long sourceScannedFileId, String relativePath) {
        this.kind = kind;
        this.sourceScannedFileId = sourceScannedFileId;
        this.relativePath = relativePath;
    }

    void refresh(Integer width, Integer height, long sizeBytes) {
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.generatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public DerivedAssetKind getKind() { return kind; }
    public Long getSourceScannedFileId() { return sourceScannedFileId; }
    public String getRelativePath() { return relativePath; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getGeneratedAt() { return generatedAt; }
}