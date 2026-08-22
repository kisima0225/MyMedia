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
@Table(name = "image_progress")
@IdClass(ImageProgress.Key.class)
public class ImageProgress {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "image_node_id")
    private Long imageNodeId;

    @Column(name = "page_index", nullable = false)
    private int pageIndex;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ImageProgress() {
    }

    ImageProgress(Long userId, Long imageNodeId) {
        this.userId = userId;
        this.imageNodeId = imageNodeId;
    }

    public Long getUserId() { return userId; }
    public Long getImageNodeId() { return imageNodeId; }
    public int getPageIndex() { return pageIndex; }
    public Instant getUpdatedAt() { return updatedAt; }

    void update(int pageIndex) {
        this.pageIndex = pageIndex;
        this.updatedAt = Instant.now();
    }

    /** JPA 复合主键类。 */
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
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(userId, other.userId)
                    && Objects.equals(imageNodeId, other.imageNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, imageNodeId);
        }
    }
}
