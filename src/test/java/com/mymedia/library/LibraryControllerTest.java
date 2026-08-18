package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LibraryControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    private String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniquePath() {
        return "/media/" + UUID.randomUUID();
    }

    @Test
    void adminCanCreateLibrary() throws Exception {
        String admin = uniqueName();
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"电影","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("电影"))
                .andExpect(jsonPath("$.domain").value("VIDEO"));
    }

    @Test
    void regularUserCannotCreateLibrary() throws Exception {
        String user = uniqueName();
        registrationService.register(user, "pw", UserRole.USER);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(user, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"电影","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyAccessibleLibraries() throws Exception {
        String user = uniqueName();
        UserAccount account = registrationService.register(user, "pw", UserRole.USER);
        MediaLibrary granted = libraryService.create("已授权", LibraryDomain.VIDEO, uniquePath());
        libraryService.create("未授权", LibraryDomain.IMAGE, uniquePath());
        accessService.grant(account.getId(), granted.getId());

        mockMvc.perform(get("/api/libraries").with(httpBasic(user, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("已授权"));
    }

    @Test
    void inaccessibleLibraryReturnsNotFound() throws Exception {
        String user = uniqueName();
        registrationService.register(user, "pw", UserRole.USER);
        MediaLibrary hidden = libraryService.create("看不见", LibraryDomain.VIDEO, uniquePath());

        // 返回 404 而非 403：不向无权访问者泄露资源是否存在
        mockMvc.perform(get("/api/libraries/" + hidden.getId()).with(httpBasic(user, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidDomain() throws Exception {
        String admin = uniqueName();
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"音乐","domain":"AUDIO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNameLongerThanDatabaseColumn() throws Exception {
        String admin = uniqueName();
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","domain":"VIDEO","rootPath":"%s"}
                                """.formatted("a".repeat(129), uniquePath())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateRootPathReturnsConflictProblemDetail() throws Exception {
        String admin = uniqueName();
        String rootPath = uniquePath();
        registrationService.register(admin, "pw", UserRole.ADMIN);
        libraryService.create("已存在", LibraryDomain.VIDEO, rootPath);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"重复路径","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(rootPath)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("请求与现有数据冲突"));
    }
}
