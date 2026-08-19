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
@Table(name = "video_progress")
@IdClass(VideoProgress.Key.class)
public class VideoProgress {

    /** 播到多少比例即视为看完。片尾曲期间用户通常直接关掉。 */
    private static final double COMPLETION_RATIO = 0.95;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "video_file_id")
    private Long videoFileId;

    @Column(name = "position_seconds", nullable = false)
    private int positionSeconds;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VideoProgress() {
    }

    VideoProgress(Long userId, Long videoFileId) {
        this.userId = userId;
        this.videoFileId = videoFileId;
    }

    public Long getUserId() { return userId; }
    public Long getVideoFileId() { return videoFileId; }
    public int getPositionSeconds() { return positionSeconds; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public boolean isCompleted() { return completed; }
    public Instant getUpdatedAt() { return updatedAt; }

    void update(int positionSeconds, Integer durationSeconds) {
        this.positionSeconds = positionSeconds;
        if (durationSeconds != null) {
            this.durationSeconds = durationSeconds;
        }
        this.completed = this.durationSeconds != null
                && this.durationSeconds > 0
                && positionSeconds >= this.durationSeconds * COMPLETION_RATIO;
        this.updatedAt = Instant.now();
    }

    /** JPA 复合主键类。 */
    public static class Key implements Serializable {

        private Long userId;
        private Long videoFileId;

        public Key() {
        }

        public Key(Long userId, Long videoFileId) {
            this.userId = userId;
            this.videoFileId = videoFileId;
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
                    && Objects.equals(videoFileId, other.videoFileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, videoFileId);
        }
    }
}
