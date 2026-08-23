package com.mymedia.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 探测结果的写入。
 *
 * <p>走 {@link JdbcTemplate} 而不是 JPA，理由和计划 03、04 一致：
 * {@code probe_raw} 是 jsonb 列，本项目一律不做 JPA 映射
 * （{@code ddl-auto=validate} 对 Hibernate 的 JSON 类型映射很挑）。
 * 顺带一条 UPDATE 就写完 8 个字段，不用先把实体读出来。
 */
@Component
class VideoProbeStore {

    private final JdbcTemplate jdbc;

    VideoProbeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void apply(Long videoFileId, VideoProbeData probe) {
        jdbc.update("""
                UPDATE video_file
                   SET duration_seconds = ?, width = ?, height = ?,
                       video_codec = ?, audio_codec = ?, bitrate = ?, container = ?,
                       probe_raw = CAST(? AS jsonb)
                 WHERE id = ?
                """,
                probe.durationSeconds(), probe.width(), probe.height(),
                truncate(probe.videoCodec(), 32), truncate(probe.audioCodec(), 32),
                probe.bitrate(), truncate(probe.container(), 16),
                probe.rawJson(), videoFileId);
    }

    /**
     * 编解码器与容器列都是有长度上限的 VARCHAR。ffprobe 偶尔会给出很长的
     * 组合名，截断比让整个任务因为一列超长而失败要好。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}