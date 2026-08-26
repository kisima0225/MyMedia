package com.mymedia.video.web;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.jobs.JobStatus;
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
import com.mymedia.video.VideoProgressService;
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
 * {@code GET /api/video/continue-watching}：断言返回的是可直接渲染卡片的字段
 * （而不是裸的进度行），并验证库访问权被撤销后条目不再出现（总览 §5 G24）。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class ContinueWatchingApiTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VideoProgressService progressService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    JdbcTemplate jdbc;

    private String username;
    private Long userId;
    private Long libraryId;
    private Long itemId;
    private Long fileId;
    private Long coverAssetId;

    @BeforeEach
    void setUp() throws IOException {
        Path file = root.resolve("电影/测试条目.mkv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        libraryId = library.getId();
        Long jobId = scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));

        VideoItem item = catalogService.findByLibrary(libraryId).getFirst();
        itemId = item.getId();
        VideoFile videoFile = catalogService.filesOf(itemId).getFirst();
        fileId = videoFile.getId();

        jdbc.update("UPDATE video_item SET title = ? WHERE id = ?", "测试条目", itemId);

        coverAssetId = jdbc.queryForObject("""
                INSERT INTO derived_asset (kind, source_scanned_file_id, relative_path, size_bytes)
                VALUES ('COVER', ?, ?, 1) RETURNING id
                """, Long.class, videoFile.getScannedFileId(), "covers/" + UUID.randomUUID());
        jdbc.update("UPDATE video_item SET cover_asset_id = ? WHERE id = ?", coverAssetId, itemId);

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, libraryId);

        progressService.record(userId, fileId, 120, 3600);
    }

    @Test
    void 继续观看返回可直接渲染卡片的字段() throws Exception {
        mockMvc.perform(get("/api/video/continue-watching").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value(fileId))
                .andExpect(jsonPath("$[0].itemId").value(itemId))
                .andExpect(jsonPath("$[0].itemTitle").value("测试条目"))
                .andExpect(jsonPath("$[0].coverAssetId").value(coverAssetId))
                .andExpect(jsonPath("$[0].positionSeconds").value(120));
    }

    @Test
    void 库访问权被撤销后继续观看不再返回该条目() throws Exception {
        // 先确认能看见
        mockMvc.perform(get("/api/video/continue-watching").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.length()").value(1));

        accessService.revoke(userId, libraryId);

        // 撤销后必须看不见——这是 G24
        mockMvc.perform(get("/api/video/continue-watching").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
