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
        "mymedia.jobs.poll-interval=PT1H"
})
class ImageProgressServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    JdbcTemplate jdbc;

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

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
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

    private MediaLibrary scannedLibrary() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
        scan(library.getId());
        return library;
    }

    private UserAccount newUser() {
        return registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
    }

    @Test
    void recordsProgressForUser() throws IOException {
        writeImage("图集/001.jpg");
        writeImage("图集/002.jpg");
        writeImage("图集/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 1);

        assertThat(progressService.find(user.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(1);
    }

    @Test
    void progressIsPerUser() throws IOException {
        writeImage("图集2/001.jpg");
        writeImage("图集2/002.jpg");
        writeImage("图集2/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount alice = newUser();
        UserAccount bob = newUser();

        progressService.record(alice.getId(), nodeId, 1);
        progressService.record(bob.getId(), nodeId, 2);

        // 用户态数据独立成表，同一本书每个用户各读各的
        assertThat(progressService.find(alice.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(1);
        assertThat(progressService.find(bob.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(2);
    }

    @Test
    void repeatedRecordsOverwriteInsteadOfAccumulating() throws IOException {
        writeImage("图集3/001.jpg");
        writeImage("图集3/002.jpg");
        writeImage("图集3/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 0);
        progressService.record(user.getId(), nodeId, 1);
        progressService.record(user.getId(), nodeId, 2);

        assertThat(progressService.continueReading(user.getId(), 20)).hasSize(0);
        assertThat(progressService.find(user.getId(), nodeId).orElseThrow().getPageIndex())
                .isEqualTo(2);
    }

    @Test
    void finishedBookDropsOutOfContinueReading() throws IOException {
        writeImage("读完的/001.jpg");
        writeImage("读完的/002.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = newUser();

        progressService.record(user.getId(), nodeId, 0);
        assertThat(progressService.continueReading(user.getId(), 20)).hasSize(1);

        progressService.record(user.getId(), nodeId, 1);   // 翻到最后一页
        assertThat(progressService.continueReading(user.getId(), 20)).isEmpty();
    }

    @Test
    void continueReadingIsOrderedByRecency() throws IOException {
        writeImage("甲/001.jpg");
        writeImage("甲/002.jpg");
        writeImage("甲/003.jpg");
        writeImage("乙/001.jpg");
        writeImage("乙/002.jpg");
        writeImage("乙/003.jpg");
        MediaLibrary library = scannedLibrary();
        var roots = catalogService.findRoots(library.getId());
        UserAccount user = newUser();

        progressService.record(user.getId(), roots.get(0).getId(), 1);
        progressService.record(user.getId(), roots.get(1).getId(), 1);

        assertThat(progressService.continueReading(user.getId(), 20))
                .extracting(ImageProgress::getImageNodeId)
                .containsExactly(roots.get(1).getId(), roots.get(0).getId());
    }

    @Test
    void rejectsNegativePageIndex() throws Exception {
        writeImage("图集4/001.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .with(httpBasic(user.getUsername(), "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointsRecordAndListProgress() throws Exception {
        writeImage("端点/001.jpg");
        writeImage("端点/002.jpg");
        writeImage("端点/003.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .with(httpBasic(user.getUsername(), "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":1}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/continue-reading")
                        .with(httpBasic(user.getUsername(), "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value(nodeId))
                .andExpect(jsonPath("$[0].pageIndex").value(1));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        writeImage("匿名/001.jpg");
        MediaLibrary library = scannedLibrary();
        Long nodeId = catalogService.findRoots(library.getId()).getFirst().getId();

        mockMvc.perform(put("/api/image/progress/" + nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":1}"))
                .andExpect(status().isUnauthorized());
    }
}