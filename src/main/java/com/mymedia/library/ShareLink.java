package com.mymedia.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条分享链接。
 *
 * <p><b>只存标量 id</b>：{@code videoItemId} / {@code imageNodeId} 是裸的 {@code Long}，
 * 不是 {@code @ManyToOne}。这不是偷懒——{@code library} 模块不许依赖 {@code video}
 * 与 {@code image}，映射成关联就必须 import 它们的实体类，
 * {@code ApplicationModules.verify()} 会当场拒绝。
 * 引用完整性由数据库的外键负责，不由 JPA 负责。
 */
@Entity
@Table(name = "share_link")
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String token;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    @Column(name = "video_item_id", updatable = false)
    private Long videoItemId;

    @Column(name = "image_node_id", updatable = false)
    private Long imageNodeId;

    @Column(name = "password_hash", updatable = false)
    private String passwordHash;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ShareLink() {
        // JPA 要求的无参构造器
    }

    ShareLink(String token, Long libraryId, Long videoItemId, Long imageNodeId,
              String passwordHash, Instant expiresAt, Long createdBy) {
        this.token = token;
        this.libraryId = libraryId;
        this.videoItemId = videoItemId;
        this.imageNodeId = imageNodeId;
        this.passwordHash = passwordHash;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public Long getLibraryId() { return libraryId; }
    public Long getVideoItemId() { return videoItemId; }
    public Long getImageNodeId() { return imageNodeId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** 只说「有没有密码」，不交出哈希。 */
    public boolean isPasswordProtected() {
        return passwordHash != null;
    }

    /** 哈希是 package-private 的：只有本模块的服务需要它去做校验。 */
    String passwordHash() {
        return passwordHash;
    }

    boolean isUsableAt(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    void revoke(Instant when) {
        if (revokedAt == null) {
            this.revokedAt = when;
        }
    }
}
