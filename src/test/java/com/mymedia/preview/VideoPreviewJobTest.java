package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(VideoPreviewJobTest.StubRunnerConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.root=target/test-derived"
})
class VideoPreviewJobTest extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRunnerConfig {

        @Bean
        @Primary
        StubCommandRunner stubCommandRunner() {
            return new StubCommandRunner();
        }
    }

    @TempDir
    Path root;

    @Autowired
    StubCommandRunner runner;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    PreviewTrigger previewTrigger;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    @BeforeEach
    void scanOneMovie() throws IOException {
        runner.reset();
        Path movie = root.resolve("电影/沙漠风暴 (2019).mp4");
        Files.createDirectories(movie.getParent());
        Files.write(movie, new byte[4096]);

        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobStatus(scanJobId)).isEqualTo("SUCCEEDED");
        });
    }

    /**
     * {@code pollOnce()} 是异步提交、同步返回——它返回不代表任务已经跑完。
     * 反复轮询直到本任务 SUCCEEDED（不能等队列排空：预览任务会再排出
     * SPRITE_GENERATE，而它的处理器要到 Task 5 才注册）。
     */
    private void runUntilSucceeded(Long jobId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        });
    }

    private String jobStatus(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
    }

    private VideoFile onlyFile() {
        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        return catalogService.filesOf(items.get(0).getId()).get(0);
    }

    @Test
    void writesProbeResultBackIntoVideoFile() {
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT duration_seconds, width, height, video_codec, audio_codec, bitrate, container,"
                        + " probe_raw::text AS raw FROM video_file WHERE id = ?", file.getId());
        assertThat(row.get("duration_seconds")).isEqualTo(600);
        assertThat(row.get("width")).isEqualTo(1920);
        assertThat(row.get("height")).isEqualTo(1080);
        assertThat(row.get("video_codec")).isEqualTo("h264");
        assertThat(row.get("audio_codec")).isEqualTo("aac");
        assertThat(row.get("bitrate")).isEqualTo(2119721L);
        assertThat(row.get("container")).isEqualTo("mov");
        assertThat((String) row.get("raw")).contains("h264");
    }

    @Test
    void generatesCoverAndThumbnailOnDisk() throws IOException {
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow();
        DerivedAsset thumbnail = assetService
                .find(DerivedAssetKind.THUMBNAIL, file.getScannedFileId()).orElseThrow();

        assertThat(Files.size(assetService.pathOf(cover))).isPositive();
        assertThat(Files.size(assetService.pathOf(thumbnail))).isPositive();
        assertThat(cover.getWidth()).isEqualTo(1600);
        // 缩略图从封面再缩，不再解一次视频
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(180);
    }

    @Test
    void seeksToTenPercentOfDurationWhenExtractingTheFrame() {
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        // 600 秒 → 60.000
        assertThat(runner.ranCommandContaining("60.000")).isTrue();
    }

    @Test
    void assignsCoverToTheOwningItem() {
        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        VideoFile file = catalogService.filesOf(item.getId()).get(0);

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        Long assetId = jdbc.queryForObject(
                "SELECT cover_asset_id FROM video_item WHERE id = ?", Long.class, item.getId());
        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow();
        assertThat(assetId).isEqualTo(cover.getId());
    }

    @Test
    void reRunningIsIdempotentAndDoesNotDuplicateAssets() {
        VideoFile file = onlyFile();

        Long firstJobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(firstJobId);
        Long secondJobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(secondJobId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ? AND kind = 'COVER'",
                Integer.class, file.getScannedFileId())).isEqualTo(1);
    }

    @Test
    void enqueuesSpriteGenerationForLongEnoughVideos() {
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        // 雪碧图任务在处理器内部排队，预览任务 SUCCEEDED 时它必然已入队
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'SPRITE_GENERATE' AND payload->>'targetId' = ?",
                Integer.class, String.valueOf(file.getId()))).isEqualTo(1);
    }

    @Test
    void skipsSpriteForVeryShortClips() {
        runner.respondToProbeWith("""
                {"streams": [{"codec_type": "video", "codec_name": "h264", "width": 320, "height": 240}],
                 "format": {"format_name": "matroska,webm", "duration": "4.000000"}}
                """);
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        runUntilSucceeded(jobId);

        // 4 秒的片子做 100 帧雪碧图没有意义；按 targetId 查，别的用例留下的任务不影响本断言
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'SPRITE_GENERATE' AND payload->>'targetId' = ?",
                Integer.class, String.valueOf(file.getId()))).isZero();
    }

    @Test
    void probeFailureFailsTheJobSoItCanBeRetried() {
        runner.failWith(1);
        VideoFile file = onlyFile();

        Long jobId = previewTrigger.requestVideoPreview(file.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobStatus(jobId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                    "SELECT attempts FROM job WHERE id = ?", Integer.class, jobId)).isEqualTo(1);
        });

        // 探测失败通常意味着文件当下读不到（盘掉了、正在写入），值得重试
    }
}
