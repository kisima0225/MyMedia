package com.mymedia.video;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class VideoBrowseControllerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    VideoBrowseService browseService;

    @Test
    void rejectsFolderFromAnotherLibraryEvenWhenRequestedLibraryIsAccessible() throws Exception {
        String username = "u" + UUID.randomUUID();
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        MediaLibrary accessible = libraryService.create(
                "可访问", LibraryDomain.VIDEO, root.resolve("accessible").toString());
        MediaLibrary hidden = libraryService.create(
                "不可访问", LibraryDomain.VIDEO, root.resolve("hidden").toString());
        accessService.grant(user.getId(), accessible.getId());

        Path hiddenFile = root.resolve("hidden/secret/hidden.mkv");
        Files.createDirectories(hiddenFile.getParent());
        Files.writeString(hiddenFile, "secret");
        scan(hidden.getId());

        VideoFolder hiddenFolder = browseService.childFolders(hidden.getId(), null).getFirst();

        mockMvc.perform(get("/api/video/browse")
                        .param("libraryId", accessible.getId().toString())
                        .param("folderId", hiddenFolder.getId().toString())
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    private void scan(Long libraryId) {
        Long jobId = scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }
}
