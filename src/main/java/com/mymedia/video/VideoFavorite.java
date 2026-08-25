package com.mymedia.video;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "video_favorite")
@IdClass(VideoFavorite.Key.class)
public class VideoFavorite {

    /** JPA 复合主键要求一个可序列化、带无参构造器、实现了 equals/hashCode 的类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long videoItemId;

        public Key() {
        }

        public Key(Long userId, Long videoItemId) {
            this.userId = userId;
            this.videoItemId = videoItemId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId)
                    && Objects.equals(videoItemId, key.videoItemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, videoItemId);
        }
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "video_item_id", nullable = false)
    private Long videoItemId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VideoFavorite() {
        // JPA 要求的无参构造器
    }

    VideoFavorite(Long userId, Long videoItemId) {
        this.userId = userId;
        this.videoItemId = videoItemId;
    }

    public Long getUserId() { return userId; }
    public Long getVideoItemId() { return videoItemId; }
    public Instant getCreatedAt() { return createdAt; }
}
