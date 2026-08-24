package com.mymedia.preview;

import tools.jackson.databind.ObjectMapper;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code SPRITE_GENERATE}：生成进度条悬停预览用的雪碧图与它的 WebVTT 索引。
 *
 * <p>帧数与网格都是固定的（默认 100 帧、10 × 10），于是永远只有一张图、一个 VTT。
 * 这一个决定省掉了多图分页、跨图边界、按时长决定图数的全部复杂度。
 *
 * <p><b>图块尺寸从生成结果读，不靠计算</b>：{@code scale=160:-2} 的高度由 ffmpeg
 * 按源视频宽高比取偶数得出，在 Java 里重算必然对不上。多读一次图换 VTT 坐标一定正确。
 */
@Component
class SpriteJobHandler implements JobHandler {

    static final String JOB_TYPE = "SPRITE_GENERATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SpriteJobHandler.class);

    private final VideoCatalogService catalog;
    private final SourceFileLocator locator;
    private final CommandRunner commandRunner;
    private final DerivedAssetService assets;
    private final PreviewProperties properties;

    SpriteJobHandler(VideoCatalogService catalog,
                     SourceFileLocator locator,
                     CommandRunner commandRunner,
                     DerivedAssetService assets,
                     PreviewProperties properties) {
        this.catalog = catalog;
        this.locator = locator;
        this.commandRunner = commandRunner;
        this.assets = assets;
        this.properties = properties;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        Long videoFileId = MAPPER.readTree(job.getPayload()).path("targetId").asLong();
        VideoFile file = catalog.getFile(videoFileId);

        Integer duration = file.getDurationSeconds();
        if (duration == null || duration < properties.spriteMinDurationSeconds()) {
            log.debug("跳过雪碧图：时长未知或过短 videoFileId={} duration={}", videoFileId, duration);
            return;
        }

        Optional<Path> source = locator.locate(file.getScannedFileId());
        if (source.isEmpty()) {
            return;
        }

        Path sheetPath = assets.prepare(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId());
        CommandResult result = commandRunner.run(
                MediaCommands.spriteSheet(properties.ffmpegPath(), source.get(), duration,
                        properties.spriteFrames(), properties.spriteColumns(),
                        properties.spriteTileWidth(), sheetPath),
                properties.commandTimeout());

        if (!result.succeeded() || !Files.exists(sheetPath) || Files.size(sheetPath) == 0) {
            log.warn("雪碧图生成失败，进度条将没有悬停预览: {} —— {}", source.get(), result.stderr());
            return;
        }

        BufferedImage sheet = ImageScaler.read(sheetPath);
        DerivedAsset sheetAsset = assets.record(DerivedAssetKind.SPRITE_SHEET,
                file.getScannedFileId(), sheet.getWidth(), sheet.getHeight());

        writeVtt(file, sheetAsset, sheet, duration);
    }

    private void writeVtt(VideoFile file, DerivedAsset sheetAsset, BufferedImage sheet,
                          int durationSeconds) throws IOException {

        int columns = properties.spriteColumns();
        int rows = (int) Math.ceil((double) properties.spriteFrames() / columns);
        int tileWidth = sheet.getWidth() / columns;
        int tileHeight = sheet.getHeight() / rows;

        String vtt = WebVttWriter.write("/api/assets/" + sheetAsset.getId(),
                properties.spriteFrames(), columns, tileWidth, tileHeight, durationSeconds);

        Path vttPath = assets.prepare(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId());
        Files.writeString(vttPath, vtt, StandardCharsets.UTF_8);
        assets.record(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId(), null, null);
    }
}
