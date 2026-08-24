package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
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
    ScrapeCandidateService candidateService;

    @Autowired
    JdbcTemplate jdbc;

    private final Set<Long> testJobIds = new HashSet<>();
    private final Set<Long> testTargetIds = new HashSet<>();
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
                "SELECT count(*) FROM job WHERE status IN ('PENDING', 'RUNNING')"
                        + " AND payload->>'libraryId' = ?",
                Integer.class, String.valueOf(library.getId()))).isZero();
    }

    private MediaLibrary scanWith(List<String> providers, String... relativePaths) throws IOException {
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
        runUntilSucceeded(scanJobId);
        rememberTargets();
        if (!providers.isEmpty()) {
            testTargetIds.forEach(this::runMetadataFor);
        }
        return library;
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
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM job WHERE type = 'METADATA_FETCH'"
                        + " AND payload->>'domain' = 'VIDEO' AND payload->>'targetId' = ?"
                        + " ORDER BY id",
                Long.class, String.valueOf(targetId));
        testJobIds.addAll(ids);
        return ids;
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
}
