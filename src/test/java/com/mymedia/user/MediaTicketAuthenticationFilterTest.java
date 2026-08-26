package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
