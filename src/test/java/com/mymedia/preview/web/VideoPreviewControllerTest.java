package com.mymedia.preview.web;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/preview/video/{videoFileId}}：按视频文件查它的派生资源 id
 * （封面、缩略图、雪碧图、雪碧图 VTT），未生成的字段为 null，无权访问 404。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class VideoPreviewControllerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

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
    private String strangerName;
    private Long videoFileId;
    private Long bareFileId;
    private Long coverAssetId;
    private Long spriteAssetId;
    private Long vttAssetId;
    private Long bareCoverAssetId;

    @BeforeEach
    void setUp() throws IOException {
        Files.write(root.resolve("沙漠风暴.mp4"), new byte[1024]);
        Files.write(root.resolve("光裸之地.mp4"), new byte[1024]);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        Long jobId = scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));

        List<Long> itemIds = catalogService.findByLibrary(library.getId()).stream()
                .map(item -> item.getId()).sorted().toList();
        VideoFile withSprite = catalogService.filesOf(itemIds.get(0)).getFirst();
        VideoFile bare = catalogService.filesOf(itemIds.get(1)).getFirst();
        videoFileId = withSprite.getId();
        bareFileId = bare.getId();

        coverAssetId = insertDerivedAsset("COVER", withSprite.getScannedFileId());
        spriteAssetId = insertDerivedAsset("SPRITE_SHEET", withSprite.getScannedFileId());
        vttAssetId = insertDerivedAsset("SPRITE_VTT", withSprite.getScannedFileId());

        // bareFileId 只造封面，不造雪碧图，模拟「还没轮到」的状态
        bareCoverAssetId = insertDerivedAsset("COVER", bare.getScannedFileId());

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        strangerName = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(strangerName, "pw", UserRole.USER);
    }

    private Long insertDerivedAsset(String kind, Long sourceScannedFileId) {
        return jdbc.queryForObject("""
                INSERT INTO derived_asset (kind, source_scanned_file_id, relative_path, size_bytes)
                VALUES (?, ?, ?, 1) RETURNING id
                """, Long.class, kind, sourceScannedFileId, kind + "/" + UUID.randomUUID());
    }

    @Test
    void 返回该视频文件的四种派生资源_id() throws Exception {
        mockMvc.perform(get("/api/preview/video/" + videoFileId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoFileId").value(videoFileId))
                .andExpect(jsonPath("$.coverAssetId").value(coverAssetId))
                .andExpect(jsonPath("$.spriteAssetId").value(spriteAssetId))
                .andExpect(jsonPath("$.spriteVttAssetId").value(vttAssetId));
    }

    @Test
    void 还没生成的资源对应字段为_null_而不是报错() throws Exception {
        // 只造了封面、没造雪碧图的文件
        mockMvc.perform(get("/api/preview/video/" + bareFileId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverAssetId").value(bareCoverAssetId))
                .andExpect(jsonPath("$.spriteAssetId").doesNotExist());
    }

    @Test
    void 无权访问该库时返回_404() throws Exception {
        mockMvc.perform(get("/api/preview/video/" + videoFileId)
                        .with(httpBasic(strangerName, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 不存在的文件返回_404() throws Exception {
        mockMvc.perform(get("/api/preview/video/999999999").with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }
}
