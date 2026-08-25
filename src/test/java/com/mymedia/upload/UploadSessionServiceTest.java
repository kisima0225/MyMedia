package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SampledHash;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UploadSessionServiceTest extends AbstractIntegrationTest {

    private static final String FAKE_HASH = "a".repeat(64);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UploadSessionService sessionService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private Long userId;
    private String username;

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void createsAReceivingSessionWithServerChosenChunking() {
        // 8MB 分片下，20MB 要 3 片（最后一片 4MB）
        UploadSession session = sessionService.create(userId, "movie.mkv",
                20L * 1024 * 1024, FAKE_HASH, library.getId());

        assertThat(session.getStatus()).isEqualTo(UploadStatus.RECEIVING);
        assertThat(session.getChunkSize()).isEqualTo(8 * 1024 * 1024);
        assertThat(session.getTotalChunks()).isEqualTo(3);
        assertThat(session.isInstant()).isFalse();
    }

    @Test
    void aFileSmallerThanOneChunkStillGetsExactlyOneChunk() {
        UploadSession session = sessionService.create(userId, "small.mp4", 12L, FAKE_HASH,
                library.getId());

        assertThat(session.getTotalChunks()).isEqualTo(1);
    }

    @Test
    void theFilenameIsSanitisedBeforeItIsStored() {
        UploadSession session = sessionService.create(userId, "../../etc/passwd.mp4",
                1024L, FAKE_HASH, library.getId());

        assertThat(session.getFilename()).isEqualTo("passwd.mp4");
    }

    @Test
    void anAlreadyPresentFileCompletesInstantly() throws Exception {
        byte[] content = "the very same bytes".repeat(64).getBytes();
        Files.write(libraryRoot.resolve("existing.mp4"), content);
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'existing.mp4', ?, now(), 'mp4') RETURNING id
                """, Long.class, library.getId(), (long) content.length);
        String hash = SampledHash.of(libraryRoot.resolve("existing.mp4"), content.length);

        UploadSession session = sessionService.create(userId, "copy.mp4",
                content.length, hash, library.getId());

        assertThat(session.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(session.isInstant()).isTrue();
        assertThat(session.getScannedFileId()).isEqualTo(scannedId);
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void cannotUploadIntoALibraryYouCannotAccess() {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());

        assertThatThrownBy(() -> sessionService.create(userId, "x.mp4", 1024L, FAKE_HASH,
                other.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void refusesAFileLargerThanTheConfiguredCeiling() {
        assertThatThrownBy(() -> sessionService.create(userId, "huge.mkv",
                100L * 1024 * 1024 * 1024, FAKE_HASH, library.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readingSomeoneElsesSessionIsNotFound() {
        UploadSession session = sessionService.create(userId, "movie.mkv", 1024L, FAKE_HASH,
                library.getId());
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);

        assertThatThrownBy(() -> sessionService.get(stranger.getId(), session.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theEndpointReportsEverythingTheClientNeedsToStartUploading() throws Exception {
        String body = """
                {"filename":"movie.mkv","totalSize":%d,"contentHash":"%s","targetLibraryId":%d}
                """.formatted(20L * 1024 * 1024, FAKE_HASH, library.getId());

        String response = mockMvc.perform(post("/api/upload/sessions")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.chunkSize").value(8 * 1024 * 1024))
                .andExpect(jsonPath("$.totalChunks").value(3))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        Long id = com.jayway.jsonpath.JsonPath.parse(response).read("$.id", Integer.class)
                .longValue();

        mockMvc.perform(get("/api/upload/sessions/{id}", id).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("movie.mkv"));
    }

    @Test
    void aMalformedHashIsRejectedAtTheEndpoint() throws Exception {
        String body = """
                {"filename":"movie.mkv","totalSize":1024,"contentHash":"nope","targetLibraryId":%d}
                """.formatted(library.getId());

        mockMvc.perform(post("/api/upload/sessions")
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
