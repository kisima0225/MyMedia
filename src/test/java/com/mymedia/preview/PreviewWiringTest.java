package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryDomain;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

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
    ImageCatalogService imageCatalogService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scanningEnqueuesPreviewsWithoutAnyManualTrigger() throws IOException {
        VideoContext context = scanVideoLibrary();

        Long previewJobId = awaitPreviewJob(context.file().getId(), "VIDEO_FILE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'targetId' = ?", Integer.class,
                String.valueOf(context.file().getId()))).isEqualTo(1);

        awaitSucceeded(previewJobId);
    }

    @Test
    void endToEndScanProducesACoverAfterThePreviewJobCompletes() throws IOException {
        VideoContext context = scanVideoLibrary();

        awaitSucceeded(awaitPreviewJob(context.file().getId(), "VIDEO_FILE"));

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();
    }

    @Test
    void wipingDerivedAssetsMakesTheNextScanRebuildEverything() throws IOException {
        VideoContext context = scanVideoLibrary();
        awaitSucceeded(awaitPreviewJob(context.file().getId(), "VIDEO_FILE"));
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();

        jdbc.update("DELETE FROM derived_asset WHERE source_scanned_file_id = ?",
                context.file().getScannedFileId());
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNull();

        Long rescanJobId = scanTrigger.requestScan(context.library().getId());
        awaitSucceeded(rescanJobId);
        awaitSucceeded(awaitPreviewJob(context.file().getId(), "VIDEO_FILE"));

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM video_item WHERE id = ?",
                Long.class, context.item().getId())).isNotNull();
    }

    @Test
    void scanningAnEpisodeIntoAnExistingCoveredItemEnqueuesItsPreview() throws IOException {
        VideoContext context = scanVideoLibrary();
        awaitSucceeded(awaitPreviewJob(context.file().getId(), "VIDEO_FILE"));

        Files.write(root.resolve("沙漠风暴 S01E02.mp4"), new byte[2048]);
        awaitSucceeded(scanTrigger.requestScan(context.library().getId()));

        VideoFile newFile = catalogService.filesOf(context.item().getId()).stream()
                .filter(file -> !file.getId().equals(context.file().getId()))
                .findFirst().orElseThrow();
        Long previewJobId = awaitPreviewJob(newFile.getId(), "VIDEO_FILE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'target' = 'VIDEO_FILE'"
                        + " AND payload->>'targetId' = ?", Integer.class,
                String.valueOf(newFile.getId()))).isEqualTo(1);

        awaitSucceeded(previewJobId);
    }

    @Test
    void backfillCapsEnqueuedVideoFilesAtFiveHundred() throws IOException {
        VideoContext context = scanVideoLibrary();
        awaitSucceeded(awaitPreviewJob(context.file().getId(), "VIDEO_FILE"));

        for (int episode = 1; episode <= 501; episode++) {
            Files.write(root.resolve("沙漠风暴 S01E%03d.mp4".formatted(episode)), new byte[128]);
        }
        awaitSucceeded(scanTrigger.requestScan(context.library().getId()));

        assertThat(catalogService.filesOf(context.item().getId())).hasSize(502);
        int queued = jdbc.queryForObject("""
                SELECT count(*)
                  FROM job j
                  JOIN video_file vf
                    ON vf.id = CAST(j.payload->>'targetId' AS BIGINT)
                  JOIN scanned_file sf ON sf.id = vf.scanned_file_id
                 WHERE j.type = 'PREVIEW_GENERATE'
                   AND j.status = 'PENDING'
                   AND j.payload->>'target' = 'VIDEO_FILE'
                   AND vf.item_id = ?
                   AND sf.id <> ?
                """, Integer.class, context.item().getId(), context.file().getScannedFileId());
        assertThat(queued).isEqualTo(500);

        jdbc.update("""
                UPDATE job j
                   SET status = 'CANCELLED', finished_at = now()
                 WHERE j.type = 'PREVIEW_GENERATE'
                   AND j.status = 'PENDING'
                   AND j.payload->>'target' = 'VIDEO_FILE'
                   AND EXISTS (
                       SELECT 1
                         FROM video_file vf
                        WHERE vf.item_id = ?
                          AND j.payload->>'targetId' = CAST(vf.id AS text)
                   )
                """, context.item().getId());
    }

    @Test
    void creatingAnImageNodeEnqueuesItsPreview() throws IOException {
        Path image = root.resolve("图集/001.png");
        Files.createDirectories(image.getParent());
        Files.write(image, pngBytes());
        MediaLibrary library = libraryService.create(
                "图库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());

        awaitSucceeded(scanTrigger.requestScan(library.getId()));

        ImageNode node = imageCatalogService.findRoots(library.getId()).getFirst();
        Long previewJobId = awaitPreviewJob(node.getId(), "IMAGE_NODE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'target' = 'IMAGE_NODE'"
                        + " AND payload->>'targetId' = ?", Integer.class,
                String.valueOf(node.getId()))).isEqualTo(1);

        awaitSucceeded(previewJobId);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isNotNull();
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

    private Long awaitPreviewJob(Long targetId, String target) {
        await().atMost(Duration.ofSeconds(10)).until(() -> findPreviewJob(targetId, target).isPresent());
        return findPreviewJob(targetId, target).orElseThrow();
    }

    private Optional<Long> findPreviewJob(Long targetId, String target) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM job WHERE type = 'PREVIEW_GENERATE'"
                        + " AND payload->>'target' = ?"
                        + " AND payload->>'targetId' = ? ORDER BY id DESC LIMIT 1",
                Long.class, target, String.valueOf(targetId));
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

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        var output = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record VideoContext(MediaLibrary library, VideoItem item, VideoFile file) {
    }
}
