package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.jobs.JobStatus;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class MetadataWriteBackTest extends AbstractIntegrationTest {

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
    JobQueue jobQueue;

    @Autowired
    VideoCatalogService videoCatalog;

    @Autowired
    ImageCatalogService imageCatalog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private MediaLibrary library;

    private VideoItem scanOneMovie() throws IOException {
        Files.write(root.resolve("沙漠风暴 (2019).mp4"), new byte[1024]);
        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobQueue.findById(scanJobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        });
        return videoCatalog.findByLibrary(library.getId()).get(0);
    }

    private ImageNode scanOneGallery() throws IOException {
        Path page = root.resolve("画集/001.png");
        Files.createDirectories(page.getParent());
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", page.toFile());

        library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobQueue.findById(scanJobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        });
        Long nodeId = jdbc.queryForObject(
                "SELECT id FROM image_node WHERE library_id = ? AND name = '画集'",
                Long.class, library.getId());
        return imageCatalog.getNode(nodeId);
    }

    private String registerUserWithAccess() {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.ADMIN);
        accessService.grant(user.getId(), library.getId());
        return username;
    }

    private static MetadataPatch patch(String source, Map<String, String> fields) {
        return new MetadataPatch(source, "42", fields, Map.of(), "{\"raw\":true}");
    }

    @Test
    void writesStandardFieldsAndRecordsTheirSource() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(), patch("TMDB", Map.of(
                MetadataFields.TITLE, "沙漠风暴",
                MetadataFields.SUMMARY, "一支小队穿越沙漠",
                MetadataFields.RELEASE_DATE, "2019-05-01",
                MetadataFields.RATING, "7.8")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, release_date, rating, scrape_status, scrape_source,"
                        + " field_sources::text AS sources FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("沙漠风暴");
        assertThat(row.get("summary")).isEqualTo("一支小队穿越沙漠");
        assertThat(row.get("release_date")).hasToString("2019-05-01");
        assertThat(row.get("rating")).hasToString("7.8");
        assertThat(row.get("scrape_status")).isEqualTo("MATCHED");
        assertThat(row.get("scrape_source")).isEqualTo("TMDB");
        assertThat((String) row.get("sources")).contains("\"title\": \"TMDB\"");
    }

    @Test
    void keepsSortTitleInStepWithTitle() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "第2部")), ScrapeStatus.MATCHED);

        // 排序键必须跟着标题走，否则改名之后列表顺序还停在旧标题上
        assertThat(jdbc.queryForObject("SELECT sort_title FROM video_item WHERE id = ?",
                String.class, item.getId())).isNotEqualTo("第2部");
    }

    @Test
    void nonStandardFieldsLandInTheMetadataJsonb() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyMetadata(item.getId(), new MetadataPatch("TMDB", "42",
                Map.of(MetadataFields.TITLE, "沙漠风暴"),
                Map.of("director", "张三", "studio", "某某映画"),
                "{}"), ScrapeStatus.MATCHED);

        String metadata = jdbc.queryForObject(
                "SELECT metadata::text FROM video_item WHERE id = ?", String.class, item.getId());
        assertThat(metadata).contains("张三").contains("某某映画");
    }

    @Test
    void preservesXmlRawResponseInJsonb() throws IOException {
        VideoItem item = scanOneMovie();
        String rawResponse = "<movie><title>沙漠风暴</title><plot>原始 NFO</plot></movie>";

        videoCatalog.applyMetadata(item.getId(), new MetadataPatch(
                "LocalNfo", "nfo-42", Map.of(MetadataFields.TITLE, "沙漠风暴"),
                Map.of(), rawResponse), ScrapeStatus.MATCHED);

        String stored = jdbc.queryForObject(
                "SELECT raw_metadata #>> '{}' FROM video_item WHERE id = ?",
                String.class, item.getId());
        assertThat(stored).isEqualTo(rawResponse);
    }

    @Test
    void userEditLocksTheFieldAndLaterScrapingCannotOverwriteIt() throws IOException {
        VideoItem item = scanOneMovie();
        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "机器给的标题",
                        MetadataFields.SUMMARY, "机器给的简介")), ScrapeStatus.MATCHED);

        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "我自己改的标题"));

        // 再刮一次：标题必须纹丝不动，简介可以更新
        videoCatalog.applyMetadata(item.getId(),
                patch("BANGUMI", Map.of(MetadataFields.TITLE, "第二次刮削的标题",
                        MetadataFields.SUMMARY, "第二次刮削的简介")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, summary, locked_fields, field_sources::text AS sources"
                        + " FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("我自己改的标题");
        assertThat(row.get("summary")).isEqualTo("第二次刮削的简介");
        assertThat((String) row.get("sources")).contains("\"title\": \"USER\"");

        MetadataSnapshot snapshot = videoCatalog.metadataOf(item.getId());
        assertThat(snapshot.lockedFields()).containsExactly(MetadataFields.TITLE);
        assertThat(snapshot.fieldSources()).containsEntry(MetadataFields.SUMMARY, "BANGUMI");
    }

    @Test
    void repeatedUserEditsDoNotDuplicateLockedFields() throws IOException {
        VideoItem item = scanOneMovie();

        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "甲"));
        videoCatalog.applyUserEdit(item.getId(), Map.of(MetadataFields.TITLE, "乙"));

        assertThat(videoCatalog.metadataOf(item.getId()).lockedFields())
                .containsExactly(MetadataFields.TITLE);
    }

    @Test
    void imageDomainPutsRatingAndReleaseDateIntoJsonbBecauseItHasNoSuchColumns() throws IOException {
        ImageNode node = scanOneGallery();

        imageCatalog.applyMetadata(node.getId(), patch("BANGUMI", Map.of(
                MetadataFields.TITLE, "某画集",
                MetadataFields.RATING, "9.1",
                MetadataFields.RELEASE_DATE, "2021-03-04")), ScrapeStatus.MATCHED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, metadata::text AS metadata FROM image_node WHERE id = ?", node.getId());
        assertThat(row.get("title")).isEqualTo("某画集");
        assertThat((String) row.get("metadata")).contains("9.1").contains("2021-03-04");
    }

    @Test
    void statusCanBeUpdatedWithoutTouchingFields() throws IOException {
        VideoItem item = scanOneMovie();
        videoCatalog.applyMetadata(item.getId(),
                patch("TMDB", Map.of(MetadataFields.TITLE, "保留我")), ScrapeStatus.MATCHED);

        videoCatalog.updateScrapeStatus(item.getId(), ScrapeStatus.NEEDS_REVIEW);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, scrape_status FROM video_item WHERE id = ?", item.getId());
        assertThat(row.get("title")).isEqualTo("保留我");
        assertThat(row.get("scrape_status")).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void concurrentUserEditCannotBeOverwrittenByScrape() throws Exception {
        VideoItem item = scanOneMovie();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch userEditApplied = new CountDownLatch(1);
        CountDownLatch releaseUserTransaction = new CountDownLatch(1);
        CountDownLatch scrapeStarted = new CountDownLatch(1);
        String applicationName = "task7-scrape-" + UUID.randomUUID().toString().replace("-", "");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var userTransaction = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                videoCatalog.applyUserEdit(item.getId(),
                        Map.of(MetadataFields.TITLE, "并发用户标题"));
                userEditApplied.countDown();
                awaitLatch(releaseUserTransaction);
            }));

            assertThat(userEditApplied.await(5, TimeUnit.SECONDS)).isTrue();

            var scrapeTransaction = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL application_name = '" + applicationName + "'");
                scrapeStarted.countDown();
                videoCatalog.applyMetadata(item.getId(),
                        patch("TMDB", Map.of(MetadataFields.TITLE, "并发机器标题")),
                        ScrapeStatus.MATCHED);
            }));

            assertThat(scrapeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(waitingTransactionCount(applicationName)).isGreaterThan(0L));

            releaseUserTransaction.countDown();
            userTransaction.get(5, TimeUnit.SECONDS);
            scrapeTransaction.get(5, TimeUnit.SECONDS);
        } finally {
            releaseUserTransaction.countDown();
        }

        assertThat(jdbc.queryForObject("SELECT title FROM video_item WHERE id = ?",
                String.class, item.getId())).isEqualTo("并发用户标题");
    }

    @Test
    void editEndpointAppliesAndLocks() throws Exception {
        VideoItem item = scanOneMovie();
        String username = registerUserWithAccess();

        mockMvc.perform(put("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"title\":\"手改标题\",\"summary\":\"手改简介\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFields", org.hamcrest.Matchers.hasSize(2)));

        mockMvc.perform(get("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.title").value("手改标题"))
                .andExpect(jsonPath("$.fieldSources.title").value("USER"));
    }

    @Test
    void editEndpointHidesItemsTheUserCannotAccess() throws Exception {
        VideoItem item = scanOneMovie();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/video/items/{id}/metadata", item.getId())
                        .with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void metadataProvidersAreReadableAndWritablePerLibrary() throws Exception {
        scanOneMovie();
        String admin = registerUserWithAccess();

        assertThat(libraryService.metadataProvidersOf(library.getId())).isEmpty();

        mockMvc.perform(put("/api/libraries/{id}/metadata-providers", library.getId())
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providers\":[\"LocalNfo\",\"TMDB\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("LocalNfo"));

        assertThat(libraryService.metadataProvidersOf(library.getId()))
                .containsExactly("LocalNfo", "TMDB");
    }

    @Test
    void metadataProvidersRoundTripValuesWithAwkwardCharacters() throws IOException {
        scanOneMovie();

        libraryService.setMetadataProviders(library.getId(), List.of("a,b", "c\"d", "{e}"));

        assertThat(libraryService.metadataProvidersOf(library.getId()))
                .containsExactly("a,b", "c\"d", "{e}");
    }

    @Test
    void adminGetsNotFoundForUnknownMetadataProviderLibrary() throws Exception {
        String admin = "a" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(get("/api/libraries/{id}/metadata-providers", Long.MAX_VALUE)
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isNotFound());
    }

    private long waitingTransactionCount(String applicationName) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_locks locks
                  JOIN pg_stat_activity activity ON activity.pid = locks.pid
                 WHERE activity.application_name = ? AND NOT locks.granted
                """, Long.class, applicationName);
        return count == null ? 0L : count;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试事务释放超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试事务被中断", e);
        }
    }
}
