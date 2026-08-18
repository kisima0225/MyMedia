package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

@AutoConfigureMockMvc
class AuthenticationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        if (registrationService != null) {
            try {
                registrationService.register("dave", "pw123", UserRole.USER);
            } catch (IllegalArgumentException ignored) {
                // 已注册过，忽略
            }
        }
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/libraries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsValidCredentials() throws Exception {
        mockMvc.perform(get("/api/libraries").with(httpBasic("dave", "pw123")))
                .andExpect(status().isOk());
    }

    @Test
    void defaultAdminCanAccessProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/libraries").with(httpBasic("admin", "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void bootstrapAdminCanCallAdminOnlyPost() throws Exception {
        String rootPath = "/media/bootstrap-" + UUID.randomUUID();

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bootstrap-test","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(rootPath)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rootPath").value(rootPath));
    }

    @Test
    void protectedEndpointRejectsWrongPassword() throws Exception {
        mockMvc.perform(get("/api/libraries").with(httpBasic("dave", "wrong")))
                .andExpect(status().isUnauthorized());
    }
}
