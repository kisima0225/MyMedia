package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImageReadingMode;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false",
        "mymedia.preview.root=target/test-derived-image-job"
})
class ImagePreviewJobTest extends AbstractIntegrationTest {

    private static final Path DERIVED_ROOT = Path.of("target/test-derived-image-job");

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

    @TempDir
    Path root;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    PreviewTrigger previewTrigger;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;

    private static byte[] pngBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var buffer = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private void writeLooseImage(String relative, int width, int height) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, pngBytes(width, height, Color.BLUE));
    }

    private void writeArchive(String relative, String... entries) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(pngBytes(800, 1200, Color.RED));
                zip.closeEntry();
            }
        }
    }

    private void scanLibrary() {
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        runUntilSucceeded(scanJobId);

        // 扫描会排出 ARCHIVE_INDEX；只等待本测试媒体库的任务，避免依赖全局队列数量。
        List<Long> indexJobIds = jdbc.queryForList("""
                SELECT j.id
                  FROM job j
                  JOIN scanned_file sf
                    ON sf.id = CAST(j.payload->>'scannedFileId' AS BIGINT)
                 WHERE j.type = 'ARCHIVE_INDEX' AND sf.library_id = ?
                """, Long.class, library.getId());
        indexJobIds.forEach(this::runUntilSucceeded);
    }

    private void scanLibraryWithoutArchiveIndex() {
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        runUntilSucceeded(scanJobId);
    }

    private ImageNode nodeNamed(String name) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND name = ?",
                Long.class, library.getId(), name);
        return catalogService.getNode(id);
    }

    /** 压缩包叶子节点的命名规则归计划 04 管，这里按 source_kind 找而不是猜名字。 */
    private ImageNode onlyArchiveNode() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND source_kind = 'ARCHIVE'",
                Long.class, library.getId());
        return catalogService.getNode(id);
    }

    /** pollOnce() 异步提交任务，因此必须等待目标任务进入终态。 */
    private void runUntilSucceeded(Long jobId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            String status = jobStatus(jobId);
            if ("PENDING".equals(status)) {
                jobPoller.pollOnce();
            }
            return "SUCCEEDED".equals(status) || "FAILED".equals(status);
        });
        assertThat(jobStatus(jobId)).as("job id=" + jobId).isEqualTo("SUCCEEDED");
    }

    private String jobStatus(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
    }

    @Test
    void makesCoverFromFirstPageOfALooseImageFolder() throws IOException {
        writeLooseImage("画师A/002.png", 900, 1400);
        writeLooseImage("画师A/001.png", 1000, 1500);
        scanLibrary();

        ImageNode node = nodeNamed("画师A");
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        runUntilSucceeded(previewJobId);

        List<ImageFile> pages = catalogService.pagesOf(node.getId());
        // 自然序的第一页是 001.png，封面必须来自它而不是先被扫到的 002.png
        Long firstPageSource = pages.get(0).getScannedFileId();
        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, firstPageSource).orElseThrow();

        assertThat(Files.size(assetService.pathOf(cover))).isPositive();
        assertThat(cover.getWidth()).isEqualTo(640);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isEqualTo(cover.getId());
    }

    @Test
    void makesCoverFromFirstEntryInsideAnArchiveWithoutExtractingIt() throws IOException {
        writeArchive("漫画/某作品 第01卷.cbz", "002.png", "001.png");
        scanLibrary();

        ImageNode node = onlyArchiveNode();
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        runUntilSucceeded(previewJobId);

        ImageFile firstPage = catalogService.pagesOf(node.getId()).get(0);
        assertThat(firstPage.getArchiveEntryName()).isEqualTo("001.png");

        DerivedAsset cover = assetService
                .find(DerivedAssetKind.COVER, firstPage.getScannedFileId()).orElseThrow();
        assertThat(cover.getWidth()).isEqualTo(640);
        assertThat(cover.getHeight()).isEqualTo(960);
        // 绝不解压到磁盘：这个来源文件在派生目录里只应有封面与缩略图两个产物
        try (var files = Files.walk(DERIVED_ROOT)) {
            assertThat(files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .startsWith(firstPage.getScannedFileId() + "-"))
                    .count()).isEqualTo(2);
        }
    }

    @Test
    void archivePreviewWaitsForIndexBeforeSucceeding() throws IOException {
        writeArchive("漫画/某作品 第01卷.cbz", "001.png");
        scanLibraryWithoutArchiveIndex();

        ImageNode node = onlyArchiveNode();
        Long archiveJobId = jdbc.queryForObject("""
                SELECT id
                  FROM job
                 WHERE type = 'ARCHIVE_INDEX'
                   AND payload->>'scannedFileId' = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, Long.class, String.valueOf(node.getArchiveScannedFileId()));
        assertThat(jobStatus(archiveJobId)).isEqualTo("PENDING");

        // 固定执行顺序：先让预览观察到“索引尚未完成”，再放行 ARCHIVE_INDEX。
        jdbc.update("UPDATE job SET scheduled_at = now() + interval '1 hour' WHERE id = ?",
                archiveJobId);
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        jobPoller.pollOnce();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jobStatus(previewJobId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                    "SELECT attempts FROM job WHERE id = ?", Integer.class, previewJobId))
                    .isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isNull();

        jdbc.update("UPDATE job SET scheduled_at = now() WHERE id = ?", archiveJobId);
        runUntilSucceeded(archiveJobId);
        jdbc.update("UPDATE job SET scheduled_at = now() WHERE id = ?", previewJobId);
        runUntilSucceeded(previewJobId);

        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isNotNull();
    }

    @Test
    void usesFreshDirectPageStateAfterArchiveReadinessIsEstablished() throws IOException {
        writeArchive("漫画/某作品 第01卷.cbz", "001.png");
        scanLibraryWithoutArchiveIndex();

        ImageNode staleNode = onlyArchiveNode();
        Long archiveJobId = jdbc.queryForObject("""
                SELECT id
                  FROM job
                 WHERE type = 'ARCHIVE_INDEX'
                   AND payload->>'scannedFileId' = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, Long.class, String.valueOf(staleNode.getArchiveScannedFileId()));
        runUntilSucceeded(archiveJobId);

        ImageNode freshNode = catalogService.getNode(staleNode.getId());
        assertThat(staleNode.getDirectPageCount()).isZero();
        assertThat(freshNode.getDirectPageCount()).isEqualTo(1);

        AtomicBoolean readinessEstablished = new AtomicBoolean();
        ProxyFactory proxyFactory = new ProxyFactory(catalogService);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("getNode")) {
                return readinessEstablished.get() ? freshNode : staleNode;
            }
            if (invocation.getMethod().getName().equals("isArchiveIndexReady")) {
                readinessEstablished.set(true);
            }
            return invocation.proceed();
        });
        ImageCatalogService instrumentedCatalog = (ImageCatalogService) proxyFactory.getProxy();

        new ImagePreviewGenerator(instrumentedCatalog, assetService, previewProperties())
                .generate(staleNode.getId());

        ImageFile firstPage = catalogService.pagesOf(staleNode.getId()).getFirst();
        assertThat(assetService.find(DerivedAssetKind.COVER, firstPage.getScannedFileId()))
                .isPresent();
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, staleNode.getId())).isNotNull();
    }

    private PreviewProperties previewProperties() {
        return new PreviewProperties(
                DERIVED_ROOT.toString(), "ffmpeg", "ffprobe", Duration.ofMinutes(2),
                640, 320, 100, 10, 160, 10);
    }

    @Test
    void indexedEmptyArchiveSucceedsWithoutRetrying() throws IOException {
        writeArchive("漫画/空卷.cbz");
        scanLibrary();

        ImageNode node = onlyArchiveNode();
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        runUntilSucceeded(previewJobId);

        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM job WHERE id = ?", Integer.class, previewJobId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, node.getId())).isNull();
    }

    @Test
    void alsoWritesAThumbnail() throws IOException {
        writeLooseImage("图集/a.png", 1000, 1000);
        scanLibrary();

        ImageNode node = nodeNamed("图集");
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        runUntilSucceeded(previewJobId);

        Long sourceId = catalogService.pagesOf(node.getId()).get(0).getScannedFileId();
        DerivedAsset thumbnail = assetService
                .find(DerivedAssetKind.THUMBNAIL, sourceId).orElseThrow();
        assertThat(thumbnail.getWidth()).isEqualTo(320);
    }

    @Test
    void nodeWithoutOwnPagesGetsNoCoverAndNoFailure() throws IOException {
        writeLooseImage("顶层/子目录/a.png", 500, 500);
        scanLibrary();

        ImageNode parent = nodeNamed("顶层");
        Long jobId = previewTrigger.requestImagePreview(parent.getId());
        runUntilSucceeded(jobId);

        // 纯中间目录没有直属页，任务应当安静成功
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, parent.getId())).isNull();
    }

    @Test
    void forceBookWithoutOwnPagesStillGetsNoCover() throws IOException {
        writeLooseImage("顶层/子目录/a.png", 500, 500);
        scanLibrary();

        ImageNode parent = nodeNamed("顶层");
        catalogService.setReadingMode(parent.getId(), ImageReadingMode.FORCE_BOOK);
        Long jobId = previewTrigger.requestImagePreview(parent.getId());
        runUntilSucceeded(jobId);

        // FORCE_BOOK 只改变阅读页集合，不改变“没有直属页就不生成节点封面”的规则。
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM image_node WHERE id = ?",
                Long.class, parent.getId())).isNull();
    }

    @Test
    void doesNotUpscaleAPageSmallerThanTheCoverWidth() throws IOException {
        writeLooseImage("小图/a.png", 200, 300);
        scanLibrary();

        ImageNode node = nodeNamed("小图");
        Long previewJobId = previewTrigger.requestImagePreview(node.getId());
        runUntilSucceeded(previewJobId);

        Long sourceId = catalogService.pagesOf(node.getId()).get(0).getScannedFileId();
        DerivedAsset cover = assetService.find(DerivedAssetKind.COVER, sourceId).orElseThrow();
        // 放大只会让文件更大、观感更糊
        assertThat(cover.getWidth()).isEqualTo(200);
    }
}
