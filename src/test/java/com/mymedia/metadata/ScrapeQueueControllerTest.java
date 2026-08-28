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
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
 * {@code GET /api/scrape/queue}：既有的 {@code /candidates?domain=&targetId=} 只能查单个目标，
 * 管理界面（Task 18 UploadView 之后的 ScrapeReviewView）需要"当前一共有哪些待确认目标"，
 * 这是给它新增的只读聚合端点（preflight 裁决 R31）。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class ScrapeQueueControllerTest extends AbstractIntegrationTest {

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
    ScrapeCandidateStore candidateStore;

    @Autowired
    JdbcTemplate jdbc;

    private VideoItem scanOneMovie(Path libRoot) throws IOException {
        Files.createDirectories(libRoot);
        Files.write(libRoot.resolve("沙漠风暴 (2019).mp4"), new byte[1024]);
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, libRoot.toString());
        Long scanJobId = scanTrigger.requestScan(library.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(jobQueue.findById(scanJobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        });
        return videoCatalog.findByLibrary(library.getId()).get(0);
    }

    private ImageNode scanOneGallery(Path libRoot) throws IOException {
        Path page = libRoot.resolve("画集/001.png");
        Files.createDirectories(page.getParent());
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", page.toFile());

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, libRoot.toString());
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

    private static MetadataCandidate candidate(String provider, double score) {
        return new MetadataCandidate(provider, "ext-" + UUID.randomUUID(), "候选标题", 2020, score, "{}");
    }

    @Test
    void queueListsPendingTargetsAcrossBothDomainsAndHidesLibrariesWithoutAccess() throws Exception {
        VideoItem accessibleItem = scanOneMovie(root.resolve("video-ok"));
        candidateStore.replaceAll(LibraryDomain.VIDEO, accessibleItem.getId(), List.of(candidate("TMDB", 0.6)));

        ImageNode accessibleNode = scanOneGallery(root.resolve("image-ok"));
        candidateStore.replaceAll(LibraryDomain.IMAGE, accessibleNode.getId(),
                List.of(candidate("Bangumi", 0.55), candidate("Bangumi", 0.45)));

        VideoItem hiddenItem = scanOneMovie(root.resolve("video-hidden"));
        candidateStore.replaceAll(LibraryDomain.VIDEO, hiddenItem.getId(), List.of(candidate("TMDB", 0.5)));

        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), accessibleItem.getLibraryId());
        accessService.grant(user.getId(), accessibleNode.getLibraryId());
        // 故意不 grant hiddenItem 所在的库

        // video_item.id 与 image_node.id 是两条独立的序列，两个域的 targetId 完全可能撞号
        // （本例就撞了：都从 1 开始）——过滤表达式必须把 domain 也纳进谓词，否则会连带
        // 匹配到另一个域里同号的目标。
        mockMvc.perform(get("/api/scrape/queue").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.domain == 'VIDEO' && @.targetId == "
                                + accessibleItem.getId() + ")].title")
                        .value(org.hamcrest.Matchers.contains(accessibleItem.getTitle())))
                .andExpect(jsonPath("$[?(@.domain == 'VIDEO' && @.targetId == "
                                + accessibleItem.getId() + ")].candidates.length()")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$[?(@.domain == 'IMAGE' && @.targetId == "
                                + accessibleNode.getId() + ")].title")
                        .value(org.hamcrest.Matchers.contains(accessibleNode.getDisplayName())))
                .andExpect(jsonPath("$[?(@.domain == 'IMAGE' && @.targetId == "
                                + accessibleNode.getId() + ")].candidates.length()")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$[?(@.domain == 'VIDEO' && @.targetId == "
                                + hiddenItem.getId() + ")]")
                        .value(org.hamcrest.Matchers.empty()));
    }
}
