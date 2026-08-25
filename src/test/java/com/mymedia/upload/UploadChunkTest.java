package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片大小压到 16 字节，这样一个 40 字节的"文件"就是 3 片，
 * 用几十个字节把整套边界跑一遍——不需要真的传 8MB。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=false",
        "mymedia.upload.chunk-size=16"
})
class UploadChunkTest extends AbstractIntegrationTest {

    private static final byte[] WHOLE = "0123456789abcdefGHIJKLMNOPQRSTUVwxyz!!!!".getBytes();
    private static final String FAKE_HASH = "b".repeat(64);

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
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private String username;
    private Long userId;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        MediaLibrary library = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());

        sessionId = sessionService.create(userId, "movie.mkv", WHOLE.length, FAKE_HASH,
                library.getId()).getId();
    }

    private byte[] chunk(int index) {
        int from = index * 16;
        int to = Math.min(from + 16, WHOLE.length);
        byte[] slice = new byte[to - from];
        System.arraycopy(WHOLE, from, slice, 0, slice.length);
        return slice;
    }

    private org.springframework.test.web.servlet.ResultActions send(int index, byte[] body)
            throws Exception {
        return mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, index)
                .with(httpBasic(username, "pw"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body));
    }

    @Test
    void aFortyByteFileIsThreeChunksWithAShortLastOne() {
        UploadSession session = sessionService.get(userId, sessionId);

        assertThat(session.getChunkSize()).isEqualTo(16);
        assertThat(session.getTotalChunks()).isEqualTo(3);
        assertThat(chunk(2)).hasSize(8);
    }

    @Test
    void chunksAreAcceptedAndShowUpInTheReceivedList() throws Exception {
        send(0, chunk(0)).andExpect(status().isNoContent());
        send(1, chunk(1)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(0, 1)));
    }

    @Test
    void chunksMayArriveOutOfOrder() throws Exception {
        send(2, chunk(2)).andExpect(status().isNoContent());
        send(0, chunk(0)).andExpect(status().isNoContent());

        // 清单永远是升序的，与到达顺序无关
        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(0, 2)));
    }

    @Test
    void resendingTheSameChunkIsIdempotent() throws Exception {
        send(1, chunk(1)).andExpect(status().isNoContent());
        send(1, chunk(1)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/upload/sessions/{id}", sessionId).with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$.receivedChunks", org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void theGapInTheListIsExactlyWhatTheClientNeedsToResend() throws Exception {
        send(0, chunk(0)).andExpect(status().isNoContent());
        send(2, chunk(2)).andExpect(status().isNoContent());

        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 2);

        send(1, chunk(1)).andExpect(status().isNoContent());

        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 1, 2);
    }

    @Test
    void aChunkThatIsTooShortIsRejectedAndLeavesNothingBehind() throws Exception {
        send(0, "short".getBytes()).andExpect(status().isBadRequest());

        assertThat(sessionService.receivedChunks(userId, sessionId)).isEmpty();
    }

    @Test
    void aChunkThatIsTooLongIsRejected() throws Exception {
        send(0, "way too many bytes for one chunk".getBytes())
                .andExpect(status().isBadRequest());

        assertThat(sessionService.receivedChunks(userId, sessionId)).isEmpty();
    }

    @Test
    void anIndexOutsideTheDeclaredRangeIsRejected() throws Exception {
        send(3, chunk(0)).andExpect(status().isBadRequest());
        send(-1, chunk(0)).andExpect(status().isBadRequest());
    }

    @Test
    void someoneElsesSessionIsNotFound() throws Exception {
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, 0)
                        .with(httpBasic(stranger, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk(0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSessionThatIsNoLongerReceivingRefusesChunks() throws Exception {
        // 秒传命中后客户端还傻传，或者合并已经开始了——两种情况都走这条路。
        // 直接 UPDATE 而不是去戳实体：这里要断言的是服务对 status 的反应
        jdbc.update("UPDATE upload_session SET status = 'COMPLETED' WHERE id = ?", sessionId);

        send(0, chunk(0)).andExpect(status().isConflict());
    }
}
