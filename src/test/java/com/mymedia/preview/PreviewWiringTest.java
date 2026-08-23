package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeAll;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(PreviewWiringTest.StubRunnerConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.root=target/test-derived-task6-wiring",
        "mymedia.preview.sprite-min-duration-seconds=3600"
})
class PreviewWiringTest extends AbstractIntegrationTest {

    private static final Path DERIVED_ROOT = Path.of("target/test-derived-task6-wiring");

    @BeforeAll
    static void clearDerivedRoot() throws IOException {
        if (!Files.exists(DERIVED_ROOT)) {
            return;
        }
        try (var paths = Files.walk(DERIVED_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

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
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scanningEnqueuesPreviewsWithoutAnyManualTrigger() throws IOException {
        VideoContext context = scanVideoLibrary();

        Long previewJobId = awaitPreviewJob(context.file().getId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'targetId' = ?", Integer.class,
                String.valueOf(context.file().getId()))).isEqualTo(1);

        awaitSucceeded(previewJobId);
    }

    @Test
    void endToEndScanProducesACoverAfterThePreviewJobCompletes() throws IOException {
        VideoContext context = scanVideoLibrary();

        awaitSucceeded(awaitPreviewJob(context.file().getId()));

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();
    }

    @Test
    void wipingDerivedAssetsMakesTheNextScanRebuildEverything() throws IOException {
        VideoContext context = scanVideoLibrary();
        awaitSucceeded(awaitPreviewJob(context.file().getId()));
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();

        jdbc.update("DELETE FROM derived_asset WHERE source_scanned_file_id = ?",
                context.file().getScannedFileId());
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNull();

        Long rescanJobId = scanTrigger.requestScan(context.library().getId());
        awaitSucceeded(rescanJobId);
        awaitSucceeded(awaitPreviewJob(context.file().getId()));

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();
    }

    private VideoContext scanVideoLibrary() throws IOException {
        Path filePath = root.resolve("沙漠风暴 (2019).mp4");
        Files.write(filePath, new byte[2048]);
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());

        awaitSucceeded(scanTrigger.requestScan(library.getId()));

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        VideoFile file = catalogService.filesOf(item.getId()).getFirst();
        return new VideoContext(library, item, file);
    }

    private Long awaitPreviewJob(Long videoFileId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> findPreviewJob(videoFileId).isPresent());
        return findPreviewJob(videoFileId).orElseThrow();
    }

    private Optional<Long> findPreviewJob(Long videoFileId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'targetId' = ? ORDER BY id DESC LIMIT 1",
                Long.class, String.valueOf(videoFileId));
        return ids.stream().findFirst();
    }

    private void awaitSucceeded(Long jobId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = jobQueue.findById(jobId).getStatus().name();
            if ("PENDING".equals(status)) {
                jobPoller.pollOnce();
            }
            assertThat(status).as("job id=" + jobId).isEqualTo("SUCCEEDED");
        });
    }

    private record VideoContext(MediaLibrary library, VideoItem item, VideoFile file) {
    }
}
