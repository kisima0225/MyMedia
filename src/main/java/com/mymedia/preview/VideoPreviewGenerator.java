package com.mymedia.preview;

import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoProbeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 一个视频文件的完整预览链：探测 → 回填 → 抽帧封面 → 缩略图 → 排队雪碧图。
 *
 * <p><b>探测必须先于抽帧</b>：封面取时长的 10% 处，不知道时长就选不出抽帧点。
 * 两者放在同一个处理器里顺序执行，而不是拆成两个任务——spec 的 job type 清单里
 * 本来也没有单独的 PROBE 类型。
 *
 * <p>失败语义分两档：
 * <ul>
 *   <li><b>探测失败 → 抛异常</b>。连容器都读不出来通常意味着文件当下不可读
 *       （盘掉了、正在写入），值得按退避重试。</li>
 *   <li><b>抽帧失败 → 记警告后返回</b>。文件可读却抽不出帧是内容问题，
 *       重试多少次都一样，让任务成功结束、条目暂时没有封面即可。</li>
 * </ul>
 */
@Component
class VideoPreviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(VideoPreviewGenerator.class);

    private final CommandRunner commandRunner;
    private final PreviewProperties properties;
    private final SourceFileLocator locator;
    private final DerivedAssetService assets;
    private final VideoCatalogService catalog;
    private final PreviewTrigger trigger;

    VideoPreviewGenerator(CommandRunner commandRunner,
                          PreviewProperties properties,
                          SourceFileLocator locator,
                          DerivedAssetService assets,
                          VideoCatalogService catalog,
                          PreviewTrigger trigger) {
        this.commandRunner = commandRunner;
        this.properties = properties;
        this.locator = locator;
        this.assets = assets;
        this.catalog = catalog;
        this.trigger = trigger;
    }

    void generate(Long videoFileId) throws IOException, InterruptedException {
        VideoFile file = catalog.getFile(videoFileId);
        Optional<Path> source = locator.locate(file.getScannedFileId());
        if (source.isEmpty()) {
            return;
        }
        Path input = source.get();

        FfprobeOutput probe = probe(input);
        catalog.applyProbe(videoFileId, new VideoProbeData(
                probe.durationSeconds(), probe.width(), probe.height(),
                probe.videoCodec(), probe.audioCodec(), probe.bitrate(),
                probe.container(), probe.rawJson()));

        Optional<Path> cover = extractCover(input, file.getScannedFileId(), probe.durationSeconds());
        if (cover.isPresent()) {
            writeAssets(file, cover.get());
        }

        Integer duration = probe.durationSeconds();
        if (duration != null && duration >= properties.spriteMinDurationSeconds()) {
            trigger.requestSprite(videoFileId);
        } else {
            log.debug("跳过雪碧图：时长 {} 秒不足 {} 秒，videoFileId={}",
                    duration, properties.spriteMinDurationSeconds(), videoFileId);
        }
    }

    private FfprobeOutput probe(Path input) throws IOException, InterruptedException {
        CommandResult result = commandRunner.run(
                MediaCommands.probe(properties.ffprobePath(), input), properties.commandTimeout());
        if (!result.succeeded()) {
            throw new IOException("ffprobe 探测失败（exit=" + result.exitCode() + "）: "
                    + input + " —— " + result.stderr());
        }
        return FfprobeParser.parse(result.stdout());
    }

    private Optional<Path> extractCover(Path input, Long sourceFileId, Integer durationSeconds)
            throws IOException, InterruptedException {

        Path output = assets.prepare(DerivedAssetKind.COVER, sourceFileId);
        CommandResult result = commandRunner.run(
                MediaCommands.coverFrame(properties.ffmpegPath(), input, durationSeconds,
                        properties.coverWidth(), output),
                properties.commandTimeout());

        if (!result.succeeded() || !Files.exists(output) || Files.size(output) == 0) {
            log.warn("抽帧失败，条目暂时没有封面: {} —— {}", input, result.stderr());
            return Optional.empty();
        }
        return Optional.of(output);
    }

    /** 封面已经在磁盘上，缩略图从它再缩一次——不再解一次视频。 */
    private void writeAssets(VideoFile file, Path coverPath) throws IOException {
        BufferedImage coverImage = ImageScaler.read(coverPath);
        DerivedAsset cover = assets.record(DerivedAssetKind.COVER, file.getScannedFileId(),
                coverImage.getWidth(), coverImage.getHeight());

        Path thumbnailPath = assets.prepare(DerivedAssetKind.THUMBNAIL, file.getScannedFileId());
        ImageScaler.Size size = ImageScaler.writeJpeg(
                coverImage, properties.thumbnailWidth(), thumbnailPath);
        assets.record(DerivedAssetKind.THUMBNAIL, file.getScannedFileId(),
                size.width(), size.height());

        boolean assigned = catalog.assignCoverIfAbsent(file.getItemId(), cover.getId());
        log.debug("封面生成完毕 videoFileId={} assetId={} 设为条目封面={}",
                file.getId(), cover.getId(), assigned);
    }
}