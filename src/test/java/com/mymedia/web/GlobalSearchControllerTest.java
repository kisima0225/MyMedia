package com.mymedia.web;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GlobalSearchControllerTest extends AbstractIntegrationTest {

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

    @BeforeEach
    void setUp() {
        MediaLibrary videoLibrary = libraryService.create(
                "视频库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        MediaLibrary imageLibrary = libraryService.create(
                "图片库" + UUID.randomUUID(), LibraryDomain.IMAGE, "/tmp/" + UUID.randomUUID());

        jdbc.update("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '进击的巨人 剧场版', '进击的巨人 剧场版')
                """, videoLibrary.getId());
        jdbc.update("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/巨人画集/', 0,
                        '巨人画集', '巨人画集', 'DIRECTORY', 40, 'ACTIVE')
                """, imageLibrary.getId());

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        accessService.grant(user.getId(), videoLibrary.getId());
        accessService.grant(user.getId(), imageLibrary.getId());
    }

    @Test
    void returnsTwoPartitionedArraysRatherThanOneMixedList() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "巨人").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("巨人"))
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.video[0].title").value("进击的巨人 剧场版"))
                .andExpect(jsonPath("$.image[0].name").value("巨人画集"));
    }

    @Test
    void aDomainWithNoHitsIsAnEmptyArrayNotAMissingField() throws Exception {
        // 前端两个分区是常驻的，缺字段会让它多写一堆判空
        mockMvc.perform(get("/api/search").param("q", "剧场版").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void blankQueryIsARequestErrorNotAnEmptyResult() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "  ").with(httpBasic(username, "pw")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "巨人"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutAnyLibraryAccessSeesBothPartitionsEmpty() throws Exception {
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8);
        registrationService.register(stranger, "pw", UserRole.USER);

        mockMvc.perform(get("/api/search").param("q", "巨人").with(httpBasic(stranger, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.hasSize(0)));
    }
}
