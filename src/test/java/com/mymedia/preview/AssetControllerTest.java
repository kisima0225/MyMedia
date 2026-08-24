package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(AssetControllerTest.StubRunnerConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.root=target/test-derived-task6-assets",
        "mymedia.preview.sprite-min-duration-seconds=3600"
})
class AssetControllerTest extends AbstractIntegrationTest {

    private static final Path DERIVED_ROOT = Path.of("target/test-derived-task6-assets");

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
    VideoCatalogService catalogService;

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    JdbcTemplate jdbc;

    private String allowedUser;
    private String strangerUser;
    private Long coverAssetId;

    @BeforeEach
    void prepare() throws IOException {
        Files.write(root.resolve("沙漠风暴 (2019).mp4"), new byte[2048]);
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        awaitSucceeded(scanTrigger.requestScan(library.getId()));

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        VideoFile file = catalogService.filesOf(item.getId()).getFirst();
        awaitSucceeded(awaitPreviewJob(file.getId()));
        coverAssetId = assetService
                .find(DerivedAssetKind.COVER, file.getScannedFileId()).orElseThrow().getId();

        allowedUser = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount allowed = registrationService.register(allowedUser, "pw", UserRole.USER);
        accessService.grant(allowed.getId(), library.getId());

        strangerUser = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(strangerUser, "pw", UserRole.USER);
    }

    @Test
    void servesTheCoverBytesToAUserWithLibraryAccess() throws Exception {
        MvcResult result = assetRequest(allowedUser, coverAssetId)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void returnsNotFoundRatherThanForbiddenForAStranger() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(strangerUser, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", coverAssetId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNotModifiedWhenTheEtagMatches() throws Exception {
        String etag = assetRequest(allowedUser, coverAssetId)
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(allowedUser, "pw"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag));
    }

    @Test
    void missingDerivedFileIsNotModifiedEvenWhenTheEtagMatches() throws Exception {
        String etag = assetRequest(allowedUser, coverAssetId)
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        Files.delete(assetService.pathOf(assetService.getById(coverAssetId)));

        mockMvc.perform(get("/api/assets/{id}", coverAssetId)
                        .with(httpBasic(allowedUser, "pw"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownAssetIsNotFound() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", 999_999_999L)
                        .with(httpBasic(allowedUser, "pw")))
                .andExpect(status().isNotFound());
    }

    private ResultActions assetRequest(String username, Long assetId) throws Exception {
        MvcResult initial = mockMvc.perform(get("/api/assets/{id}", assetId)
                        .with(httpBasic(username, "pw")))
                .andExpect(request().asyncStarted())
                .andReturn();
        initial.getAsyncResult(Duration.ofSeconds(5).toMillis());
        return mockMvc.perform(asyncDispatch(initial));
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

}
