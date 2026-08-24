package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.awaitility.Awaitility.await;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class ImageBrowseServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mockMvc;

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
    ImageBrowseService browseService;

    private String username;

    /** 轮询到队列排空为止，理由见 Global Constraints「需要跑任务的集成测试」。 */
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

    private MediaLibrary setUpLibrary() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scan(library.getId());

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());
        return library;
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
    }

    @Test
    void breadcrumbResolvesAncestorsWithoutRecursiveQuery() throws IOException {
        writeImage("一层/二层/三层/001.jpg");
        MediaLibrary library = setUpLibrary();

        ImageNode l1 = catalogService.findRoots(library.getId()).getFirst();
        ImageNode l2 = browseService.childNodes(library.getId(), l1.getId()).getFirst();
        ImageNode l3 = browseService.childNodes(library.getId(), l2.getId()).getFirst();

        assertThat(browseService.breadcrumb(l3.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("一层", "二层", "三层");
    }

    @Test
    void childNodesAreSortedNaturally() throws IOException {
        writeImage("第10卷/001.jpg");
        writeImage("第2卷/001.jpg");
        writeImage("第1卷/001.jpg");
        MediaLibrary library = setUpLibrary();

        assertThat(browseService.childNodes(library.getId(), null))
                .extracting(ImageNode::getName)
                .containsExactly("第1卷", "第2卷", "第10卷");
    }

    @Test
    void browseEndpointReturnsBreadcrumbAndChildren() throws Exception {
        writeImage("作者/系列/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode author = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/browse")
                        .param("libraryId", String.valueOf(library.getId()))
                        .param("nodeId", String.valueOf(author.getId()))
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breadcrumb[0].name").value("作者"))
                .andExpect(jsonPath("$.nodes[0].name").value("系列"));
    }

    @Test
    void nodeDetailExposesBothCapabilities() throws Exception {
        writeImage("混合/封面.jpg");
        writeImage("混合/子目录/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode mixed = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/nodes/" + mixed.getId())
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true))
                .andExpect(jsonPath("$.browsable").value(true))
                .andExpect(jsonPath("$.directPageCount").value(1))
                .andExpect(jsonPath("$.childNodeCount").value(1));
    }

    @Test
    void forceFolderHidesTheReadingEntry() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(put("/api/image/nodes/" + node.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FORCE_FOLDER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false))
                .andExpect(jsonPath("$.browsable").value(true));
    }

    @Test
    void forceBookGivesAReadingEntryEvenWithoutDirectPages() throws Exception {
        writeImage("作品/第1话/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getDirectPageCount()).isZero();

        mockMvc.perform(put("/api/image/nodes/" + work.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FORCE_BOOK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true))
                .andExpect(jsonPath("$.browsable").value(false));
    }

    @Test
    void rejectsUnknownReadingMode() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(put("/api/image/nodes/" + node.getId() + "/reading-mode")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"随便看看\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        // 404 而非 403：不向无权访问者泄露资源是否存在
        mockMvc.perform(get("/api/image/nodes/" + node.getId()).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void strangerCannotBrowseNodesOfAnInaccessibleLibrary() throws Exception {
        writeImage("可见/001.jpg");
        MediaLibrary visible = setUpLibrary();

        writeImage("hidden/秘密/001.jpg");
        MediaLibrary hidden = libraryService.create(
                "隐藏" + UUID.randomUUID(), LibraryDomain.IMAGE, root.resolve("hidden").toString());
        scan(hidden.getId());
        ImageNode hiddenRoot = catalogService.findRoots(hidden.getId()).getFirst();

        mockMvc.perform(get("/api/image/browse")
                        .param("libraryId", String.valueOf(visible.getId()))
                        .param("nodeId", String.valueOf(hiddenRoot.getId()))
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        writeImage("图集/001.jpg");
        MediaLibrary library = setUpLibrary();
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();

        mockMvc.perform(get("/api/image/nodes/" + node.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void topLevelListingOnlyCoversAccessibleImageLibraries() throws Exception {
        writeImage("可见/001.jpg");
        MediaLibrary visible = setUpLibrary();

        Files.createDirectories(root.resolve("hidden"));
        MediaLibrary hidden = libraryService.create(
                "隐藏" + UUID.randomUUID(), LibraryDomain.IMAGE, root.resolve("hidden").toString());
        scan(hidden.getId());

        mockMvc.perform(get("/api/image/nodes").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(
                        catalogService.findRoots(visible.getId()).size()));
    }
}
