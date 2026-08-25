package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ShareLinkControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private String username;
    private Long userId;
    private Long itemId;
    private Long nodeId;

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    private Long insertNode(Long libraryId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', 8, 'ACTIVE')
                RETURNING id
                """, Long.class, libraryId, name, name, name);
    }

    @BeforeEach
    void setUp() {
        MediaLibrary videoLibrary = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        MediaLibrary imageLibrary = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.IMAGE, "/tmp/" + UUID.randomUUID());
        itemId = insertItem(videoLibrary.getId(), "沙漠风暴");
        nodeId = insertNode(imageLibrary.getId(), "夏日画集");

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, videoLibrary.getId());
        accessService.grant(userId, imageLibrary.getId());
    }

    @Test
    void createsAShareLinkForAVideoItem() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("VIDEO"))
                .andExpect(jsonPath("$.targetId").value(itemId))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.passwordProtected").value(false));
    }

    @Test
    void createsAShareLinkForAnImageNode() throws Exception {
        mockMvc.perform(post("/api/image/nodes/{id}/share", nodeId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\",\"expiresInDays\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domain").value("IMAGE"))
                .andExpect(jsonPath("$.targetId").value(nodeId))
                .andExpect(jsonPath("$.passwordProtected").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void theResponseNeverCarriesThePasswordHash() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void cannotShareSomethingInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(),
                LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(post("/api/video/items/{id}/share", hidden)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiresInDaysIsRejectedWhenOutOfRange() throws Exception {
        mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\":9999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsAndRevokesThroughTheManagementEndpoints() throws Exception {
        String body = mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long shareId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Integer.class)
                .longValue();

        mockMvc.perform(get("/api/shares").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(shareId));

        mockMvc.perform(delete("/api/shares/{id}", shareId).with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/shares").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
    }

    @Test
    void revokingSomeoneElsesLinkIsNotFound() throws Exception {
        String body = mockMvc.perform(post("/api/video/items/{id}/share", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString();
        Long shareId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Integer.class)
                .longValue();

        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(delete("/api/shares/{id}", shareId).with(httpBasic(stranger, "pw")))
                .andExpect(status().isNotFound());
    }
}
