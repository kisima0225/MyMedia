package com.mymedia.scan;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.jobs.JobStatus;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.scan.event.ScannedFileChanged;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileRelocated;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(LibraryScanIntegrationTest.EventRecorderConfig.class)
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H"
})
class LibraryScanIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    EventRecorder recorder;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
    }

    private void writeMedia(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content-" + relative);
    }

    @Test
    void scanIsEnqueuedAsJob() {
        MediaLibrary library = libraryAtRoot();

        Long jobId = scanTrigger.requestScan(library.getId());

        assertThat(jobQueue.findById(jobId).getType()).isEqualTo("LIBRARY_SCAN");
        assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.PENDING);
        runAndAwaitSuccess(jobId);
    }

    @Test
    void repeatedScanRequestsDoNotStack() {
        MediaLibrary library = libraryAtRoot();

        Long first = scanTrigger.requestScan(library.getId());
        Long second = scanTrigger.requestScan(library.getId());

        assertThat(second).isEqualTo(first);
        runAndAwaitSuccess(first);
    }

    @Test
    void discoveredFilesPublishEvents() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");
        recorder.clear();

        Long jobId = scanTrigger.requestScan(library.getId());
        runPendingJobs();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(recorder.discovered())
                        .extracting(ScannedFileDiscovered::relativePath)
                        .contains("电影/片子.mkv"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    void changedFilePublishesUpdatedMetadataAfterReconciliation() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");
        scanner.scan(library.getId());
        recorder.clear();

        Path file = root.resolve("电影/片子.mkv");
        Files.writeString(file, "changed-content-with-a-different-size");
        Instant expectedMtime = Instant.parse("2026-08-18T00:00:01.123456Z");
        Files.setLastModifiedTime(file, FileTime.from(expectedMtime));

        scanner.scan(library.getId());

        assertThat(recorder.changed()).singleElement().satisfies(event -> {
            assertThat(event.reactivated()).isFalse();
            assertThat(event.relativePath()).isEqualTo("电影/片子.mkv");
            assertThat(event.sizeBytes()).isEqualTo(Files.size(file));
            assertThat(event.mtime()).isEqualTo(ScannedFile.toPostgresPrecision(expectedMtime));
        });
        assertThat(recorder.discovered()).isEmpty();
        assertThat(recorder.vanished()).isEmpty();
        assertThat(recorder.relocated()).isEmpty();
        assertThat(recorder.ordered()).extracting(Object::getClass)
                .containsExactly(ScannedFileChanged.class, LibraryScanCompleted.class);
    }

    @Test
    void samePathRecoveryPublishesReactivatedChangeWithoutDiscovery() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("恢复.mkv");
        scanner.scan(library.getId());

        Files.delete(root.resolve("恢复.mkv"));
        scanner.scan(library.getId());
        recorder.clear();

        writeMedia("恢复.mkv");
        scanner.scan(library.getId());

        assertThat(recorder.changed()).singleElement()
                .extracting(ScannedFileChanged::reactivated)
                .isEqualTo(true);
        assertThat(recorder.discovered()).isEmpty();
        assertThat(recorder.vanished()).isEmpty();
        assertThat(recorder.relocated()).isEmpty();
        assertThat(recorder.ordered()).extracting(Object::getClass)
                .containsExactly(ScannedFileChanged.class, LibraryScanCompleted.class);
    }

    @Test
    void relocationPublishesOnlyRelocatedBeforeCompletion() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("旧位置.mkv");
        scanner.scan(library.getId());
        recorder.clear();

        Files.move(root.resolve("旧位置.mkv"), root.resolve("新位置.mkv"));
        scanner.scan(library.getId());

        assertThat(recorder.relocated()).hasSize(1);
        assertThat(recorder.discovered()).isEmpty();
        assertThat(recorder.vanished()).isEmpty();
        assertThat(recorder.changed()).isEmpty();
        assertThat(recorder.ordered()).extracting(Object::getClass)
                .containsExactly(ScannedFileRelocated.class, LibraryScanCompleted.class);
    }

    @Test
    void vanishedFilePublishesBeforeCompletion() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("消失.mkv");
        scanner.scan(library.getId());
        recorder.clear();

        Files.delete(root.resolve("消失.mkv"));
        scanner.scan(library.getId());

        assertThat(recorder.vanished()).hasSize(1);
        assertThat(recorder.discovered()).isEmpty();
        assertThat(recorder.changed()).isEmpty();
        assertThat(recorder.relocated()).isEmpty();
        assertThat(recorder.ordered()).extracting(Object::getClass)
                .containsExactly(ScannedFileVanished.class, LibraryScanCompleted.class);
    }

    @Test
    void adminCanTriggerScanViaApi() throws Exception {
        MediaLibrary library = libraryAtRoot();
        String admin = "a" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(admin, "pw", UserRole.ADMIN);

        MvcResult result = mockMvc.perform(post("/api/libraries/" + library.getId() + "/scan")
                        .with(httpBasic(admin, "pw")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNumber())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        Long jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asLong();
        runAndAwaitSuccess(jobId);
    }

    @Test
    void regularUserCannotTriggerScan() throws Exception {
        MediaLibrary library = libraryAtRoot();
        String user = "u" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(user, "pw", UserRole.USER);

        mockMvc.perform(post("/api/libraries/" + library.getId() + "/scan")
                        .with(httpBasic(user, "pw")))
                .andExpect(status().isForbidden());
    }

    private void runPendingJobs() {
        jobPoller.pollOnce();
    }

    private void runAndAwaitSuccess(Long jobId) {
        runPendingJobs();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @TestConfiguration
    static class EventRecorderConfig {

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    static class EventRecorder {

        private final List<ScannedFileDiscovered> discovered = new CopyOnWriteArrayList<>();
        private final List<ScannedFileVanished> vanished = new CopyOnWriteArrayList<>();
        private final List<ScannedFileRelocated> relocated = new CopyOnWriteArrayList<>();
        private final List<ScannedFileChanged> changed = new CopyOnWriteArrayList<>();
        private final List<Object> ordered = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ScannedFileDiscovered event) {
            discovered.add(event);
            ordered.add(event);
        }

        @EventListener
        void on(ScannedFileVanished event) {
            vanished.add(event);
            ordered.add(event);
        }

        @EventListener
        void on(ScannedFileRelocated event) {
            relocated.add(event);
            ordered.add(event);
        }

        @EventListener
        void on(ScannedFileChanged event) {
            changed.add(event);
            ordered.add(event);
        }

        @EventListener
        void on(LibraryScanCompleted event) {
            ordered.add(event);
        }

        List<ScannedFileDiscovered> discovered() {
            return discovered;
        }

        List<ScannedFileVanished> vanished() {
            return vanished;
        }

        List<ScannedFileRelocated> relocated() {
            return relocated;
        }

        List<ScannedFileChanged> changed() {
            return changed;
        }

        List<Object> ordered() {
            return ordered;
        }

        void clear() {
            discovered.clear();
            vanished.clear();
            relocated.clear();
            changed.clear();
            ordered.clear();
        }
    }
}
