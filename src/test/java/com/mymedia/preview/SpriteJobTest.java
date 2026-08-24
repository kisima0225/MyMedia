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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(SpriteJobTest.StubRunnerConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false",
        "mymedia.preview.root=target/test-derived"
})
class SpriteJobTest extends AbstractIntegrationTest {

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

    private VideoFile file;

    @BeforeEach
    void scanAndProbe() throws IOException {
        runner.reset();
        Path movie = root.resolve("沙漠风暴 (2019).mp4");
        Files.write(movie, new byte[4096]);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        runUntilSucceeded(scanTrigger.requestScan(library.getId()));

        VideoItem item = catalogService.findByLibrary(library.getId()).get(0);
        file = catalogService.filesOf(item.getId()).get(0);

        // 雪碧图依赖已探测出的时长，先跑一次预览生成
        runner.produceImageOfSize(1600, 900);
        runUntilSucceeded(previewTrigger.requestVideoPreview(file.getId()));
    }

    @Test
    void generatesExactlyOneSheetAndOneVtt() {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        assertThat(assetService.find(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId()))
                .isPresent();
        assertThat(assetService.find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()))
                .isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?"
                        + " AND kind LIKE 'SPRITE%'", Integer.class, file.getScannedFileId()))
                .isEqualTo(2);
    }

    @Test
    void derivesTileGeometryFromTheGeneratedSheetRatherThanRecomputingIt() throws IOException {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();
        String content = Files.readString(assetService.pathOf(vtt));

        // 1600 × 900 的图切成 10 × 10 → 每块 160 × 90
        assertThat(content).contains("#xywh=0,0,160,90");
        assertThat(content).contains("#xywh=1440,810,160,90");
    }

    @Test
    void vttPointsAtTheSheetAssetEndpoint() throws IOException {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        DerivedAsset sheet = assetService
                .find(DerivedAssetKind.SPRITE_SHEET, file.getScannedFileId()).orElseThrow();
        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();

        assertThat(Files.readString(assetService.pathOf(vtt)))
                .contains("/api/assets/" + sheet.getId() + "#xywh=");
    }

    @Test
    void cuesSpanTheWholeDuration() throws IOException {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        DerivedAsset vtt = assetService
                .find(DerivedAssetKind.SPRITE_VTT, file.getScannedFileId()).orElseThrow();
        String content = Files.readString(assetService.pathOf(vtt));

        // 桩探测返回 600 秒
        assertThat(content).startsWith("WEBVTT");
        assertThat(content).contains("00:00:00.000 --> 00:00:06.000");
        assertThat(content).contains("--> 00:10:00.000");
    }

    @Test
    void usesFpsThatFitsOneHundredFramesIntoTheDuration() {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        // 100 帧 / 600 秒
        assertThat(runner.ranCommandContaining("fps=0.166667,scale=160:-2,tile=10x10")).isTrue();
    }

    @Test
    void rerunOverwritesInsteadOfAccumulating() {
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));
        runUntilSucceeded(previewTrigger.requestSprite(file.getId()));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?"
                        + " AND kind = 'SPRITE_SHEET'", Integer.class, file.getScannedFileId()))
                .isEqualTo(1);
    }

    private void runUntilSucceeded(Long jobId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            jobPoller.pollOnce();
            String status = jobStatus(jobId);
            return "SUCCEEDED".equals(status) || "FAILED".equals(status);
        });
        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
    }

    private String jobStatus(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
    }
}
