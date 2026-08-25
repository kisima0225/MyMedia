package com.mymedia.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "image_favorite")
@IdClass(ImageFavorite.Key.class)
public class ImageFavorite {

    /** JPA 复合主键要求一个可序列化、带无参构造器、实现了 equals/hashCode 的类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long imageNodeId;

        public Key() {
        }

        public Key(Long userId, Long imageNodeId) {
            this.userId = userId;
            this.imageNodeId = imageNodeId;
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
                    && Objects.equals(imageNodeId, key.imageNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, imageNodeId);
        }
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "image_node_id", nullable = false)
    private Long imageNodeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ImageFavorite() {
        // JPA 要求的无参构造器
    }

    ImageFavorite(Long userId, Long imageNodeId) {
        this.userId = userId;
        this.imageNodeId = imageNodeId;
    }

    public Long getUserId() { return userId; }
    public Long getImageNodeId() { return imageNodeId; }
    public Instant getCreatedAt() { return createdAt; }
}
