package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    private String createUser() {
        String username = "auth-" + UUID.randomUUID();
        registrationService.register(username, "pw-" + username, UserRole.USER);
        return username;
    }

    @Test
    void me_返回当前用户() throws Exception {
        String username = createUser();

        mockMvc.perform(get("/api/auth/me").with(httpBasic(username, "pw-" + username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.userId").isNumber());
    }

    @Test
    void me_未认证时返回_401_且不带_WWW_Authenticate_头() throws Exception {
        // 带了这个头，浏览器会弹出原生密码框，SPA 的登录页就再也没机会出现
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void me_密码错误时返回_401() throws Exception {
        String username = createUser();

        mockMvc.perform(get("/api/auth/me").with(httpBasic(username, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void media_ticket_签发的票据能被服务解析回同一个用户() throws Exception {
        String username = createUser();

        String body = mockMvc.perform(post("/api/auth/media-ticket")
                        .with(httpBasic(username, "pw-" + username)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("ticket");
    }

    @Test
    void media_ticket_未认证时返回_401() throws Exception {
        mockMvc.perform(post("/api/auth/media-ticket"))
                .andExpect(status().isUnauthorized());
    }
}
