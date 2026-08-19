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

@Entity
@Table(name = "video_file")
public class VideoFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 指向物理层。文件改名或移动时只有 {@code scanned_file.relative_path} 变化，
     * 本表与用户播放进度完全不受影响 —— 这是 spec 6.1 分层设计的收益。
     */
    @Column(name = "scanned_file_id", nullable = false, updatable = false)
    private Long scannedFileId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "group_id")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VideoFileRole role = VideoFileRole.PRIMARY;

    @Column(name = "episode_index")
    private Integer episodeIndex;

    @Column(name = "sort_key", nullable = false)
    private String sortKey = "";

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "video_codec", length = 32)
    private String videoCodec;

    @Column(name = "audio_codec", length = 32)
    private String audioCodec;

    @Column
    private Long bitrate;

    @Column(length = 16)
    private String container;

    protected VideoFile() {
    }

    VideoFile(Long scannedFileId, Long itemId, VideoFileRole role, String sortSource) {
        this.scannedFileId = scannedFileId;
        this.itemId = itemId;
        this.role = role;
        this.sortKey = NaturalSortKey.of(sortSource);
    }

    public Long getId() { return id; }
    public Long getScannedFileId() { return scannedFileId; }
    public Long getItemId() { return itemId; }
    public Long getGroupId() { return groupId; }
    public VideoFileRole getRole() { return role; }
    public Integer getEpisodeIndex() { return episodeIndex; }
    public String getSortKey() { return sortKey; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getVideoCodec() { return videoCodec; }
    public String getAudioCodec() { return audioCodec; }

    void assignGroup(Long groupId, Integer episodeIndex) {
        this.groupId = groupId;
        this.episodeIndex = episodeIndex;
    }

    /** 由计划 05 的 ffprobe 探测结果回填。 */
    void applyProbe(Integer durationSeconds, Integer width, Integer height,
                    String videoCodec, String audioCodec, Long bitrate, String container) {
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.bitrate = bitrate;
        this.container = container;
    }
}
