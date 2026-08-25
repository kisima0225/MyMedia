package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片 → 断点续传 → 合并 → 校验 → 落库 → 触发扫描，一条链路走到底。
 *
 * <p>分片大小压到 16 字节：整条链路的每一个边界都能用几十个字节跑到，
 * 不需要真的搬 8MB。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mymedia.jobs.enabled=false",
        "mymedia.upload.chunk-size=16",
        // @TestPropertySource 的值必须是编译期常量，拿不到 @TempDir 生成的路径，
        // 所以临时目录用一个固定的相对路径。target/ 本来就会被 mvn clean 清掉
        "mymedia.upload.temp-root=./target/test-uploads"
})
class UploadEndToEndTest extends AbstractIntegrationTest {

    private static final byte[] WHOLE = "0123456789abcdefGHIJKLMNOPQRSTUVwxyz!!!!".getBytes();

    private static final Path UPLOAD_TEMP = Path.of("./target/test-uploads");

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
    JobPoller jobPoller;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path scratchDir;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private String username;
    private Long userId;
    private String wholeHash;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(UPLOAD_TEMP);

        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());

        // 客户端会算的那个哈希：这里用一个临时文件算出同样的值。
        // 它必须落在媒体库<b>之外</b>，否则会被扫描当成一个新文件
        Path scratch = Files.write(scratchDir.resolve("scratch.bin"), WHOLE);
        wholeHash = SampledHash.of(scratch, WHOLE.length);
    }

    private byte[] chunk(int index) {
        int from = index * 16;
        int to = Math.min(from + 16, WHOLE.length);
        byte[] slice = new byte[to - from];
        System.arraycopy(WHOLE, from, slice, 0, slice.length);
        return slice;
    }

    private void send(Long sessionId, int index, byte[] body) throws Exception {
        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, index)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    private Long newSession(String filename) {
        return sessionService.create(userId, filename, WHOLE.length, wholeHash, library.getId())
                .getId();
    }

    @Test
    void aResumedUploadEndsUpInTheLibraryAndGetsScanned() throws Exception {
        Long sessionId = newSession("movie.mkv");

        // 第一次只传两片就"断线"了
        send(sessionId, 0, chunk(0));
        send(sessionId, 2, chunk(2));
        assertThat(sessionService.receivedChunks(userId, sessionId)).containsExactly(0, 2);
        assertThat(sessionService.get(userId, sessionId).getStatus())
                .isEqualTo(UploadStatus.RECEIVING);

        // 续传缺的那一片，会话立刻转入合并
        send(sessionId, 1, chunk(1));
        assertThat(sessionService.get(userId, sessionId).getStatus())
                .isEqualTo(UploadStatus.ASSEMBLING);

        jobPoller.pollOnce();   // UPLOAD_ASSEMBLE

        UploadSession done = sessionService.get(userId, sessionId);
        assertThat(done.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(done.getRelativePath()).isEqualTo("movie.mkv");
        assertThat(libraryRoot.resolve("movie.mkv")).exists();
        assertThat(Files.readAllBytes(libraryRoot.resolve("movie.mkv"))).isEqualTo(WHOLE);

        // 临时目录清干净了，半成品不会留在磁盘上
        assertThat(Files.exists(UPLOAD_TEMP.resolve(String.valueOf(sessionId)))).isFalse();

        jobPoller.pollOnce();   // 合并时排出的 LIBRARY_SCAN

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM scanned_file WHERE library_id = ? AND relative_path = 'movie.mkv'
                """, Integer.class, library.getId())).isEqualTo(1);
    }

    @Test
    void aSecondUploadOfTheSameBytesIsInstantAndCopiesNothing() throws Exception {
        Long first = newSession("movie.mkv");
        send(first, 0, chunk(0));
        send(first, 1, chunk(1));
        send(first, 2, chunk(2));
        jobPoller.pollOnce();
        jobPoller.pollOnce();   // 让扫描把 scanned_file 建出来

        UploadSession second = sessionService.create(userId, "movie-copy.mkv",
                WHOLE.length, wholeHash, library.getId());

        assertThat(second.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(second.isInstant()).isTrue();
        // 秒传不产生第二份物理副本——这正是内容寻址的意义
        assertThat(Files.exists(libraryRoot.resolve("movie-copy.mkv"))).isFalse();
    }

    @Test
    void aNameCollisionGetsASuffixInsteadOfOverwriting() throws Exception {
        Files.write(libraryRoot.resolve("movie.mkv"), "someone else's file".getBytes());

        Long sessionId = newSession("movie.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, chunk(2));
        jobPoller.pollOnce();

        assertThat(sessionService.get(userId, sessionId).getRelativePath())
                .isEqualTo("movie (2).mkv");
        // 别人的文件一个字节都没动
        assertThat(Files.readString(libraryRoot.resolve("movie.mkv")))
                .isEqualTo("someone else's file");
    }

    @Test
    void aHashMismatchFailsTheSessionAndDoesNotRetryForever() throws Exception {
        // 声明的哈希是对的，但最后一片送的是别的内容
        Long sessionId = newSession("tampered.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, "XXXXXXXX".getBytes());   // 长度对，内容不对

        jobPoller.pollOnce();

        UploadSession failed = sessionService.get(userId, sessionId);
        assertThat(failed.getStatus()).isEqualTo(UploadStatus.FAILED);
        assertThat(failed.getLastError()).contains("哈希");
        assertThat(Files.exists(libraryRoot.resolve("tampered.mkv"))).isFalse();

        // 任务本身算成功——再合一百遍结果也一样，重试只是浪费
        assertThat(jdbc.queryForObject("""
                SELECT status FROM job WHERE type = 'UPLOAD_ASSEMBLE'
                  AND payload->>'sessionId' = ?
                """, String.class, String.valueOf(sessionId))).isEqualTo("SUCCEEDED");
    }

    @Test
    void theAssembleJobIsEnqueuedExactlyOnce() throws Exception {
        Long sessionId = newSession("once.mkv");
        send(sessionId, 0, chunk(0));
        send(sessionId, 1, chunk(1));
        send(sessionId, 2, chunk(2));
        // 重传最后一片：count 仍然等于 totalChunks，但状态已经不是 RECEIVING 了
        mockMvc.perform(put("/api/upload/sessions/{id}/chunks/{index}", sessionId, 2)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk(2)))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM job WHERE type = 'UPLOAD_ASSEMBLE'
                  AND payload->>'sessionId' = ?
                """, Integer.class, String.valueOf(sessionId))).isEqualTo(1);
    }
}
