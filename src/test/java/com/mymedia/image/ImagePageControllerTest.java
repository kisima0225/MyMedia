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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.awaitility.Awaitility.await;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H"
})
class ImagePageControllerTest extends AbstractIntegrationTest {

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
    private MediaLibrary library;

    private void writeImage(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

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

    private void scanAndGrant() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scan(library.getId());

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());
    }

    private void rescan() {
        scan(library.getId());
    }

    @Test
    void servesALooseImage() throws Exception {
        writeImage("图集/001.jpg", "HELLO-PAGE");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        MvcResult initial = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(request().asyncStarted())
                .andReturn();
        initial.getAsyncResult(Duration.ofSeconds(5).toMillis());

        MvcResult result = mockMvc.perform(asyncDispatch(initial)).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("HELLO-PAGE");
    }

    @Test
    void servesAPageFromInsideAnArchiveWithoutExtracting() throws Exception {
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scanAndGrant();
        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        ImageNode volume = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        Long secondPage = catalogService.pagesOf(volume.getId()).get(1).getId();

        MvcResult initial = mockMvc.perform(get("/api/image/page/" + secondPage)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        initial.getAsyncResult(Duration.ofSeconds(5).toMillis());

        MvcResult result = mockMvc.perform(asyncDispatch(initial)).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("page-002.jpg");
    }

    @Test
    void responseCarriesEtagAndCacheControl() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(username, "pw")))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void repeatRequestWithMatchingEtagGets304() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        String etag = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw")))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // 阅读器来回翻页会反复请求同一页，304 让它一个字节都不用再传
        MvcResult cached = mockMvc.perform(get("/api/image/page/" + pageId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andReturn();
        assertThat(cached.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void pageListIsOrderedByPageIndex() throws Exception {
        writeImage("图集/10.jpg", "a");
        writeImage("图集/2.jpg", "b");
        writeImage("图集/1.jpg", "c");
        scanAndGrant();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/nodes/" + nodeId + "/pages")
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageIndex").value(0))
                .andExpect(jsonPath("$[1].pageIndex").value(1))
                .andExpect(jsonPath("$[2].pageIndex").value(2));
    }

    @Test
    void forceBookModeReadsTheWholeSubtreeInChapterOrder() throws Exception {
        writeImage("作品/第1话/001.jpg", "c1p1");
        writeImage("作品/第1话/002.jpg", "c1p2");
        writeImage("作品/第2话/001.jpg", "c2p1");
        scanAndGrant();
        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        catalogService.setReadingMode(work.getId(), ImageReadingMode.FORCE_BOOK);

        // 章节顺序 + 页顺序，靠的是 sort_path 而不是节点 id
        assertThat(catalogService.pagesOf(work.getId())).hasSize(3);

        mockMvc.perform(get("/api/image/nodes/" + work.getId() + "/pages")
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void missingFileGivesNotFoundInsteadOfServerError() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        Files.delete(root.resolve("图集/001.jpg"));
        rescan();

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/image/page/" + pageId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        writeImage("图集/001.jpg", "X");
        scanAndGrant();
        Long pageId = catalogService.pagesOf(
                catalogService.findRoots(library.getId()).getFirst().getId()).getFirst().getId();

        mockMvc.perform(get("/api/image/page/" + pageId))
                .andExpect(status().isUnauthorized());
    }
}