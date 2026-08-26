package com.mymedia.image.web;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImageProgressService;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/image/continue-reading}：结构与 {@code ContinueWatchingApiTest} 对称，
 * 断言可直接渲染卡片的字段，并验证库访问权被撤销后条目不再出现（总览 §5 G24）。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class ContinueReadingApiTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImageProgressService progressService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    JdbcTemplate jdbc;

    private String username;
    private Long userId;
    private Long libraryId;
    private Long nodeId;
    private Long coverAssetId;

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    /** 轮询到队列排空为止，抄自 {@code ImageProgressServiceTest} 的同名助手。 */
    private void scan(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(pendingOrRunningJobs()).isZero();
        });
    }

    private int pendingOrRunningJobs() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE status IN ('PENDING', 'RUNNING')", Integer.class);
    }

    @BeforeEach
    void setUp() throws IOException {
        writeImage("测试条目/001.jpg");
        writeImage("测试条目/002.jpg");
        writeImage("测试条目/003.jpg");

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        libraryId = library.getId();
        scan(libraryId);

        ImageNode node = catalogService.findRoots(libraryId).getFirst();
        nodeId = node.getId();
        ImageFile firstPage = catalogService.pagesOf(nodeId).getFirst();

        coverAssetId = jdbc.queryForObject("""
                INSERT INTO derived_asset (kind, source_scanned_file_id, relative_path, size_bytes)
                VALUES ('COVER', ?, ?, 1) RETURNING id
                """, Long.class, firstPage.getScannedFileId(), "covers/" + UUID.randomUUID());
        jdbc.update("UPDATE image_node SET cover_asset_id = ? WHERE id = ?", coverAssetId, nodeId);

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, libraryId);

        progressService.record(userId, nodeId, 1);
    }

    @Test
    void 继续阅读返回可直接渲染卡片的字段() throws Exception {
        mockMvc.perform(get("/api/image/continue-reading").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value(nodeId))
                .andExpect(jsonPath("$[0].nodeTitle").value("测试条目"))
                .andExpect(jsonPath("$[0].coverAssetId").value(coverAssetId))
                .andExpect(jsonPath("$[0].pageIndex").value(1))
                .andExpect(jsonPath("$[0].totalPageCount").value(3));
    }

    @Test
    void 库访问权被撤销后继续阅读不再返回该条目() throws Exception {
        // 先确认能看见
        mockMvc.perform(get("/api/image/continue-reading").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.length()").value(1));

        accessService.revoke(userId, libraryId);

        // 撤销后必须看不见——这是 G24
        mockMvc.perform(get("/api/image/continue-reading").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
