package com.mymedia.video;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoStreamControllerTest extends AbstractIntegrationTest {

    private static final String CONTENT = "0123456789ABCDEFGHIJ";

    @TempDir
    Path root;

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
    VideoCatalogService catalogService;

    private String username;
    private Long fileId;

    private void setUpLibraryWithFile() throws IOException {
        Path file = root.resolve("电影/片子.mkv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, CONTENT);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        fileId = catalogService.filesOf(item.getId()).getFirst().getId();
    }

    @Test
    void fullRequestReturns200WithAcceptRanges() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo(CONTENT);
    }

    @Test
    void rangeRequestReturns206WithContentRange() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/20"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("01234");
    }

    @Test
    void openEndedRangeReadsToEnd() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=15-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 15-19/20"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("FGHIJ");
    }

    @Test
    void suffixRangeReadsLastBytes() throws Exception {
        setUpLibraryWithFile();

        MvcResult result = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 17-19/20"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("HIJ");
    }

    @Test
    void unsatisfiableRangeReturns416WithContentRange() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=999-1999"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */20"));
    }

    @Test
    void malformedRangeFallsBackToFullContent() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=abc-def"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()));
    }

    @Test
    void responseCarriesEtag() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId).with(httpBasic(username, "pw")))
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    @Test
    void ifRangeMismatchReturnsFullContent() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId)
                        .with(httpBasic(username, "pw"))
                        .header(HttpHeaders.RANGE, "bytes=0-4")
                        .header(HttpHeaders.IF_RANGE, "\"stale-etag\""))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()));
    }

    @Test
    void userWithoutLibraryAccessGetsNotFound() throws Exception {
        setUpLibraryWithFile();
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/video/stream/" + fileId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        setUpLibraryWithFile();

        mockMvc.perform(get("/api/video/stream/" + fileId))
                .andExpect(status().isUnauthorized());
    }
}
