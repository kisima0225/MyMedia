package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(MetadataFetchJobTest.DirectProviderFailureConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false",
        "mymedia.preview.root=target/test-derived-metadata"
})
class MetadataFetchJobTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    VideoCatalogService videoCatalog;

    @Autowired
    ImageCatalogService imageCatalog;

    @Autowired
    ScrapeCandidateService candidateService;

    @Autowired
    JdbcTemplate jdbc;

    private final Set<Long> testJobIds = new HashSet<>();
    private final Set<Long> testTargetIds = new HashSet<>();
    private final Set<Long> testImageTargetIds = new HashSet<>();
    private MediaLibrary library;

    /** 本测试失败时也不把自己留下的待执行任务带给后续测试。 */
    @AfterEach
    void cancelTestJobs() {
        if (library == null) {
            return;
        }

        List<Object> arguments = new ArrayList<>();
        arguments.add(String.valueOf(library.getId()));
        StringBuilder predicate = new StringBuilder("payload->>'libraryId' = ?");
        if (!testTargetIds.isEmpty()) {
            predicate.append(" OR (type = 'METADATA_FETCH' AND payload->>'domain' = 'VIDEO'")
                    .append(" AND payload->>'targetId' IN (")
                    .append("?, ".repeat(Math.max(0, testTargetIds.size() - 1)))
                    .append("?))");
            arguments.addAll(testTargetIds.stream().map(String::valueOf).toList());
        }
        if (!testImageTargetIds.isEmpty()) {
            predicate.append(" OR (type = 'METADATA_FETCH' AND payload->>'domain' = 'IMAGE'")
                    .append(" AND payload->>'targetId' IN (")
                    .append("?, ".repeat(Math.max(0, testImageTargetIds.size() - 1)))
                    .append("?))");
            arguments.addAll(testImageTargetIds.stream().map(String::valueOf).toList());
        }
        if (!testJobIds.isEmpty()) {
            predicate.append(" OR id IN (")
                    .append("?, ".repeat(Math.max(0, testJobIds.size() - 1)))
                    .append("?)");
            arguments.addAll(testJobIds);
        }

        jdbc.update("UPDATE job SET status = 'CANCELLED', finished_at = now(),"
                        + " lease_owner = NULL, lease_expires_at = NULL"
                        + " WHERE status IN ('PENDING', 'RUNNING') AND (" + predicate + ")",
                arguments.toArray());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE status IN ('PENDING', 'RUNNING') AND ("
                        + predicate + ")",
                Integer.class, arguments.toArray())).isZero();
    }

    private MediaLibrary scanWith(List<String> providers, String... relativePaths) throws IOException {
        return scanWith(providers, true, relativePaths);
    }

    private MediaLibrary scanWithoutMetadata(List<String> providers, String... relativePaths)
            throws IOException {
        return scanWith(providers, false, relativePaths);
    }

    private MediaLibrary scanWith(List<String> providers, boolean processMetadata,
                                  String... relativePaths) throws IOException {
        for (String relative : relativePaths) {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            if (relative.endsWith(".nfo")) {
                Files.writeString(file, """
                        <movie>
                          <title>大雄兔</title>
                          <plot>一只巨兔与三个坏蛋的故事。</plot>
                          <premiered>2008-05-20</premiered>
                          <set><name>Blender 开源电影</name></set>
                        </movie>
                        """, StandardCharsets.UTF_8);
            } else {
                Files.write(file, new byte[1024]);
            }
        }
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        libraryService.setMetadataProviders(library.getId(), providers);

        Long scanJobId = scanTrigger.requestScan(library.getId());
        runScanUntilSucceeded(scanJobId);
        rememberTargets();
        if (processMetadata && !providers.isEmpty()) {
            testTargetIds.forEach(this::runMetadataFor);
        }
        return library;
    }

    private MediaLibrary scanImageArchive() throws IOException {
        Path archive = root.resolve("漫画/作品.cbz");
        Files.createDirectories(archive.getParent());
        Files.writeString(root.resolve("漫画/作品.nfo"), """
                <movie>
                  <title>归档作品</title>
                  <plot>归档主体路径回归。</plot>
                </movie>
                """, StandardCharsets.UTF_8);
        try (var output = Files.newOutputStream(archive);
             var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("001.png"));
            zip.write(pngBytes());
            zip.closeEntry();
        }

        library = libraryService.create(
                "图库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        libraryService.setMetadataProviders(library.getId(), List.of("LocalNfo"));

        Long scanJobId = scanTrigger.requestScan(library.getId());
        runScanUntilSucceeded(scanJobId);
        testImageTargetIds.addAll(jdbc.queryForList(
                "SELECT id FROM image_node WHERE library_id = ?", Long.class, library.getId()));
        return library;
    }

    private Long archiveNodeId() {
        Long nodeId = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND source_kind = 'ARCHIVE'",
                Long.class, library.getId());
        testImageTargetIds.add(nodeId);
        return nodeId;
    }

    private Long archiveIndexJobId(Long nodeId) {
        Long scannedFileId = jdbc.queryForObject(
                "SELECT archive_scanned_file_id FROM image_node WHERE id = ?",
                Long.class, nodeId);
        Long jobId = jdbc.queryForObject("""
                SELECT id FROM job
                 WHERE type = 'ARCHIVE_INDEX'
                   AND payload->>'scannedFileId' = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, Long.class, String.valueOf(scannedFileId));
        testJobIds.add(jobId);
        return jobId;
    }

    private void rememberTargets() {
        testTargetIds.addAll(videoCatalog.findByLibrary(library.getId()).stream()
                .map(VideoItem::getId)
                .toList());
    }

    private void runMetadataFor(Long targetId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(metadataJobIds(targetId)).isNotEmpty());
        metadataJobIds(targetId).forEach(this::runUntilSucceeded);
    }

    private List<Long> metadataJobIds(Long targetId) {
        return metadataJobIds(LibraryDomain.VIDEO, targetId);
    }

    private List<Long> metadataJobIds(LibraryDomain domain, Long targetId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM job WHERE type = 'METADATA_FETCH'"
                        + " AND payload->>'domain' = ? AND payload->>'targetId' = ?"
                        + " ORDER BY id",
                Long.class, domain.name(), String.valueOf(targetId));
        testJobIds.addAll(ids);
        return ids;
    }

    private Long awaitMetadataJob(LibraryDomain domain, Long targetId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(metadataJobIds(domain, targetId)).isNotEmpty());
        return metadataJobIds(domain, targetId).getLast();
    }

    /** 扫描只主动抢占一次，避免等待扫描完成时提前抢占其下游任务。 */
    private void runScanUntilSucceeded(Long jobId) {
        testJobIds.add(jobId);
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).as("scan job id=" + jobId).isEqualTo("SUCCEEDED"));
    }

    private static byte[] pngBytes() throws IOException {
        var image = new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private void runUntilSucceeded(Long jobId) {
        testJobIds.add(jobId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String current = jobStatus(jobId);
            if (!"SUCCEEDED".equals(current) && !"FAILED".equals(current)) {
                jobPoller.pollOnce();
            }
            assertThat(jobStatus(jobId)).as("job id=" + jobId).isEqualTo("SUCCEEDED");
        });
    }

    private String jobStatus(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
    }

    private VideoItem onlyItem() {
        List<VideoItem> items = videoCatalog.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private String registerAdmin() {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.ADMIN);
        accessService.grant(user.getId(), library.getId());
        return username;
    }

    @Test
    void scrapesFromTheLocalNfoWithoutAnyNetworkAccess() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");

        VideoItem item = onlyItem();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, release_date, scrape_status, scrape_source"
                        + " FROM video_item WHERE id = ?", item.getId());

        assertThat(row.get("title")).isEqualTo("大雄兔");
        assertThat(row.get("summary")).isEqualTo("一只巨兔与三个坏蛋的故事。");
        assertThat(row.get("release_date")).hasToString("2008-05-20");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(row.get("scrape_source")).isEqualTo("LocalNfo");
    }

    @Test
    void fillsTheCollectionFromTheKodiSetTag() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");

        VideoItem item = onlyItem();
        String collectionName = jdbc.queryForObject("""
                SELECT c.name FROM collection c
                  JOIN collection_item ci ON ci.collection_id = c.id
                 WHERE ci.video_item_id = ?
                """, String.class, item.getId());

        assertThat(collectionName).isEqualTo("Blender 开源电影");
    }

    @Test
    void collectionIsFoundOrCreatedRatherThanDuplicated() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        VideoItem item = onlyItem();
        int firstRound = metadataJobIds(item.getId()).size();

        jdbc.update("UPDATE video_item SET scrape_status = 'PENDING' WHERE id = ?", item.getId());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        runUntilSucceeded(scanJobId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(metadataJobIds(item.getId()).size()).isGreaterThan(firstRound));
        runUntilSucceeded(metadataJobIds(item.getId()).getLast());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM collection WHERE library_id = ?",
                Integer.class, library.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM collection_item WHERE video_item_id = ?",
                Integer.class, item.getId())).isEqualTo(1);
    }

    @Test
    void withoutAnyLocalFileItFallsBackToTheFilenameQuietly() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/某个自制视频.mp4");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT scrape_status, scrape_source FROM video_item WHERE id = ?",
                onlyItem().getId());

        assertThat(row.get("scrape_status")).isEqualTo("NO_MATCH");
        assertThat(row.get("scrape_source")).isEqualTo("Filename");
    }

    @Test
    void libraryWithoutProvidersIsMarkedNotApplicableAndNoJobIsEnqueued() throws IOException {
        scanWith(List.of(), "电影/某个自制视频.mp4");

        VideoItem item = onlyItem();
        assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                String.class, item.getId())).isEqualTo("NOT_APPLICABLE");
        assertThat(metadataJobIds(item.getId())).isEmpty();

        jdbc.update("UPDATE video_item SET scrape_status = 'PENDING' WHERE id = ?", item.getId());
        runUntilSucceeded(scanTrigger.requestScan(library.getId()));

        assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                String.class, item.getId())).isEqualTo("NOT_APPLICABLE");
        assertThat(metadataJobIds(item.getId())).isEmpty();
    }

    @Test
    void directProviderFailureMarksItemErrorBeforeSchedulerRetry() throws IOException {
        scanWithoutMetadata(List.of("DirectProviderFailure"), "电影/网络故障.mp4");

        VideoItem item = onlyItem();
        Long metadataJobId = awaitMetadataJob(LibraryDomain.VIDEO, item.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jobStatus(metadataJobId);
            if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
                jobPoller.pollOnce();
            }
            assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                    String.class, item.getId())).isEqualTo("ERROR");
            assertThat(jdbc.queryForObject("SELECT attempts FROM job WHERE id = ?",
                    Integer.class, metadataJobId)).isEqualTo(1);
            assertThat(jobStatus(metadataJobId)).isEqualTo("PENDING");
        });
    }

    @Test
    void imageArchiveUsesItsScannedPathBeforeArchiveIndexRuns() throws IOException {
        scanImageArchive();

        Long nodeId = archiveNodeId();
        Long archiveJobId = archiveIndexJobId(nodeId);
        Long metadataJobId = awaitMetadataJob(LibraryDomain.IMAGE, nodeId);

        // 强制先执行归档主体的元数据任务，验证它不依赖 image_file 已经建立。
        jdbc.update("UPDATE job SET scheduled_at = now() + interval '1 hour' WHERE id = ?",
                archiveJobId);
        runUntilSucceeded(metadataJobId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, scrape_status, scrape_source FROM image_node WHERE id = ?", nodeId);
        assertThat(row.get("title")).isEqualTo("归档作品");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(row.get("scrape_source")).isEqualTo("LocalNfo");

        jdbc.update("UPDATE job SET scheduled_at = now() WHERE id = ?", archiveJobId);
        runUntilSucceeded(archiveJobId);
        assertThat(imageCatalog.pagesOf(nodeId)).hasSize(1);
    }

    @Test
    void rescanDoesNotReScrapeAnAlreadyMatchedItem() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        VideoItem item = onlyItem();
        int firstRound = metadataJobIds(item.getId()).size();

        Long scanJobId = scanTrigger.requestScan(library.getId());
        runUntilSucceeded(scanJobId);

        assertThat(metadataJobIds(item.getId())).hasSize(firstRound);
    }

    @Test
    void candidateEndpointsListConfirmAndIgnore() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        String admin = registerAdmin();

        jdbc.update("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, year, score, payload)
                VALUES (?, 'LocalNfo', 'c1', '候选一', 2008, 0.62, '{}'::jsonb),
                       (?, 'LocalNfo', 'c2', '候选二', 2009, 0.55, '{}'::jsonb)
                """, item.getId(), item.getId());
        videoCatalog.updateScrapeStatus(item.getId(), ScrapeStatus.NEEDS_REVIEW);

        mockMvc.perform(get("/api/scrape/candidates")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("候选一"));

        mockMvc.perform(post("/api/scrape/ignore")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isNoContent());

        assertThat(candidateService.candidatesFor(LibraryDomain.VIDEO, item.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT scrape_status FROM video_item WHERE id = ?",
                String.class, item.getId())).isEqualTo("NO_MATCH");
    }

    @Test
    void confirmingACandidateAppliesItAndClearsTheQueue() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4", "电影/大雄兔.nfo");
        VideoItem item = onlyItem();
        String admin = registerAdmin();
        jdbc.update("UPDATE video_item SET title = '待确认', scrape_status = 'NEEDS_REVIEW' WHERE id = ?",
                item.getId());

        Long candidateId = jdbc.queryForObject("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, year, score, payload)
                VALUES (?, 'LocalNfo', '大雄兔.nfo', '大雄兔', 2008, 0.62, '{}'::jsonb)
                RETURNING id
                """, Long.class, item.getId());

        mockMvc.perform(post("/api/scrape/candidates/{id}/confirm", candidateId)
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.title").value("大雄兔"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, scrape_status FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("大雄兔");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(candidateService.candidatesFor(LibraryDomain.VIDEO, item.getId())).isEmpty();
    }

    @Test
    void candidatesAreRemovedWhenTheItemIsDeleted() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        jdbc.update("""
                INSERT INTO scrape_candidate (video_item_id, provider, external_id, title, score, payload)
                VALUES (?, 'LocalNfo', 'c1', '候选', 0.5, '{}'::jsonb)
                """, item.getId());

        jdbc.update("DELETE FROM video_item WHERE id = ?", item.getId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scrape_candidate", Integer.class)).isZero();
    }

    @Test
    void candidateRowMustPointAtExactlyOneTarget() throws IOException {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO scrape_candidate (provider, external_id, title, score, payload)
                VALUES ('LocalNfo', 'c1', '无主候选', 0.5, '{}'::jsonb)
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void unauthorisedUserCannotSeeCandidates() throws Exception {
        scanWith(List.of("LocalNfo"), "电影/大雄兔.mp4");
        VideoItem item = onlyItem();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/scrape/candidates")
                        .param("domain", "VIDEO").param("targetId", String.valueOf(item.getId()))
                        .with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DirectProviderFailureConfig {

        @Bean
        MetadataProvider directProviderFailure() {
            return new MetadataProvider() {
                @Override
                public String name() {
                    return "DirectProviderFailure";
                }

                @Override
                public boolean supports(LibraryDomain domain) {
                    return domain == LibraryDomain.VIDEO;
                }

                @Override
                public boolean available() {
                    throw new ProviderUnavailableException("模拟 HTTP 429");
                }

                @Override
                public List<MetadataCandidate> search(ScrapeSubject subject) {
                    return List.of();
                }

                @Override
                public Optional<com.mymedia.shared.MetadataPatch> fetch(
                        ScrapeSubject subject, MetadataCandidate candidate) {
                    return Optional.empty();
                }
            };
        }
    }
}
