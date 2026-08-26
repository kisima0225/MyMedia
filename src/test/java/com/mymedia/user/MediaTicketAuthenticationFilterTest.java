package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 票据过滤器的行为契约。
 *
 * <p>这里断言的是<b>认证是否发生</b>，不是<b>资源是否存在</b>：
 * 用不存在的 id 请求，认证成功的结果是 404（走到了控制器、没找到东西），
 * 认证失败的结果是 401（根本没进控制器）。两者泾渭分明，正好用来分辨过滤器有没有生效。
 */
@AutoConfigureMockMvc
class MediaTicketAuthenticationFilterTest extends AbstractIntegrationTest {

    private static final long MISSING_ID = 999_999_999L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MediaTicketService mediaTicketService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    UserQueryService userQueryService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private String ticketForNewUser() {
        String username = "ticket-" + UUID.randomUUID();
        registrationService.register(username, "pw-" + username, UserRole.USER);
        Long userId = userQueryService.findByUsername(username).orElseThrow().getId();
        return mediaTicketService.issue(userId, Instant.now()).ticket();
    }

    @Test
    void 有效票据让视频流端点通过认证() throws Exception {
        mockMvc.perform(get("/api/video/stream/" + MISSING_ID).param("ticket", ticketForNewUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 有效票据让图片页端点通过认证() throws Exception {
        mockMvc.perform(get("/api/image/page/" + MISSING_ID).param("ticket", ticketForNewUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 有效票据让派生资源端点通过认证() throws Exception {
        mockMvc.perform(get("/api/assets/" + MISSING_ID).param("ticket", ticketForNewUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 没有票据也没有_Basic_头时仍然是_401() throws Exception {
        mockMvc.perform(get("/api/video/stream/" + MISSING_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 伪造的票据不通过() throws Exception {
        mockMvc.perform(get("/api/video/stream/" + MISSING_ID)
                        .param("ticket", "1.99999999999.forged-signature"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 畸形票据不通过且不抛异常() throws Exception {
        for (String bad : new String[]{"", "   ", "garbage", "1.2", "a.b.c"}) {
            mockMvc.perform(get("/api/video/stream/" + MISSING_ID).param("ticket", bad))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 票据在白名单之外的路径上完全无效() throws Exception {
        String ticket = ticketForNewUser();

        // 这是本任务最重要的一条断言：票据不是完整身份。
        // 拿它去读媒体库列表、发起扫描、建分享链接，一律 401。
        mockMvc.perform(get("/api/libraries").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/video/items").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/shares").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 指向已删除用户的票据不通过() throws Exception {
        // 用一个绝不存在的用户 id 签一张形式完全合法的票据
        String ticket = mediaTicketService.issue(MISSING_ID, Instant.now()).ticket();

        mockMvc.perform(get("/api/video/stream/1").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 已停用账号的票据不通过() throws Exception {
        String username = "ticket-" + UUID.randomUUID();
        UserAccount account = registrationService.register(username, "pw-" + username, UserRole.USER);
        String ticket = mediaTicketService.issue(account.getId(), Instant.now()).ticket();

        // DatabaseUserDetailsService.loadUserByUsername 对停用账号不抛异常，只是把
        // enabled=false 塞进返回的 UserDetails 里；过滤器必须自己查 isEnabled()。
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", account.getId());

        mockMvc.perform(get("/api/video/stream/" + MISSING_ID).param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 票据遇到已认证的_Basic_头时以_Basic_身份为准() throws Exception {
        // A 持有票据但没有这个库的访问权；B 用 Basic 登录且有访问权。
        // 若票据错误地覆盖了 Basic 身份，请求会以「A 无权访问」收场，
        // VideoStreamService.locate 把无权访问伪装成 404（ADR：404 而非 403）；
        // 若 Basic 身份正确胜出，请求会真的读到文件内容，200。
        // 两种结果肉眼可辨，用来钉住「Basic 优先于票据」这条端到端保证。
        byte[] content = "basic-identity-wins".getBytes();
        Files.write(tempDir.resolve("probe.mp4"), content);

        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, tempDir.toString());
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'probe.mp4', ?, now(), 'mp4') RETURNING id
                """, Long.class, library.getId(), (long) content.length);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', 'probe', 'probe') RETURNING id
                """, Long.class, library.getId());
        Long fileId = jdbc.queryForObject("""
                INSERT INTO video_file (item_id, scanned_file_id, role)
                VALUES (?, ?, 'PRIMARY') RETURNING id
                """, Long.class, itemId, scannedId);

        String usernameA = "ticket-a-" + UUID.randomUUID();
        UserAccount userA = registrationService.register(usernameA, "pw-" + usernameA, UserRole.USER);
        // 故意不给 A 授权这个库

        String usernameB = "basic-b-" + UUID.randomUUID();
        String passwordB = "pw-" + UUID.randomUUID();
        UserAccount userB = registrationService.register(usernameB, passwordB, UserRole.USER);
        accessService.grant(userB.getId(), library.getId());

        String ticketForA = mediaTicketService.issue(userA.getId(), Instant.now()).ticket();

        MvcResult initial = mockMvc.perform(get("/api/video/stream/" + fileId)
                        .param("ticket", ticketForA)
                        .with(httpBasic(usernameB, passwordB)))
                .andExpect(request().asyncStarted())
                .andReturn();
        initial.getAsyncResult(Duration.ofSeconds(5).toMillis());

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content));
    }
}
