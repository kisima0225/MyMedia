package com.mymedia.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 预览生成的全部可调项。
 *
 * <p>{@code root} 独立于任何媒体库路径——派生资源可以整个删掉重建，
 * 而媒体库里放的是用户不可替代的原始文件，两者绝不能混在一起。
 */
@ConfigurationProperties(prefix = "mymedia.preview")
record PreviewProperties(
        String root,
        String ffmpegPath,
        String ffprobePath,
        Duration commandTimeout,
        int coverWidth,
        int thumbnailWidth,
        int spriteFrames,
        int spriteColumns,
        int spriteTileWidth,
        int spriteMinDurationSeconds) {

    PreviewProperties {
        root = root == null ? "./data/derived" : root;
        ffmpegPath = ffmpegPath == null ? "ffmpeg" : ffmpegPath;
        ffprobePath = ffprobePath == null ? "ffprobe" : ffprobePath;
        commandTimeout = commandTimeout == null ? Duration.ofMinutes(2) : commandTimeout;
        coverWidth = coverWidth <= 0 ? 640 : coverWidth;
        thumbnailWidth = thumbnailWidth <= 0 ? 320 : thumbnailWidth;
        spriteFrames = spriteFrames <= 0 ? 100 : spriteFrames;
        spriteColumns = spriteColumns <= 0 ? 10 : spriteColumns;
        spriteTileWidth = spriteTileWidth <= 0 ? 160 : spriteTileWidth;
        spriteMinDurationSeconds = spriteMinDurationSeconds <= 0 ? 10 : spriteMinDurationSeconds;
    }
}
