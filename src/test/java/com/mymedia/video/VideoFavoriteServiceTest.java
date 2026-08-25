package com.mymedia.video;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoFavoriteServiceTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VideoFavoriteService favoriteService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long itemId;
    private Long userId;
    private String username;

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        itemId = insertItem(library.getId(), "沙漠风暴");

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void addsAndRemovesAFavorite() {
        favoriteService.add(userId, itemId);
        assertThat(favoriteService.isFavorite(userId, itemId)).isTrue();

        favoriteService.remove(userId, itemId);
        assertThat(favoriteService.isFavorite(userId, itemId)).isFalse();
    }

    @Test
    void addingTwiceIsIdempotent() {
        favoriteService.add(userId, itemId);
        favoriteService.add(userId, itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_favorite WHERE user_id = ? AND video_item_id = ?",
                Integer.class, userId, itemId)).isEqualTo(1);
    }

    @Test
    void removingSomethingNotFavoritedIsNotAnError() {
        favoriteService.remove(userId, itemId);

        assertThat(favoriteService.isFavorite(userId, itemId)).isFalse();
    }

    @Test
    void favoritesAreStrictlyPerUser() {
        UserAccount other = registrationService.register(
                "o" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(other.getId(), library.getId());

        favoriteService.add(userId, itemId);

        assertThat(favoriteService.isFavorite(other.getId(), itemId)).isFalse();
        assertThat(favoriteService.listItems(other.getId(), 20)).isEmpty();
    }

    @Test
    void listsNewestFirst() throws InterruptedException {
        Long second = insertItem(library.getId(), "雪原突击");
        favoriteService.add(userId, itemId);
        // created_at 的精度是微秒，但两次插入可能落在同一微秒里，睡一下把顺序钉死
        Thread.sleep(10);
        favoriteService.add(userId, second);

        assertThat(favoriteService.listItems(userId, 20))
                .extracting(VideoItem::getId)
                .containsExactly(second, itemId);
    }

    @Test
    void deletingAnItemRemovesItFromEveryonesFavorites() {
        favoriteService.add(userId, itemId);

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_favorite WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void endpointsToggleAndList() throws Exception {
        mockMvc.perform(put("/api/video/items/{id}/favorite", itemId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/video/favorites").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("沙漠风暴"));

        mockMvc.perform(delete("/api/video/items/{id}/favorite", itemId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/video/favorites").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void cannotFavoriteAnItemInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(put("/api/video/items/{id}/favorite", hidden)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }
}
