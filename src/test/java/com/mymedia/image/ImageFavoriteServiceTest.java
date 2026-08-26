package com.mymedia.image;

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
class ImageFavoriteServiceTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImageFavoriteService favoriteService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long nodeId;
    private Long userId;
    private String username;

    /** 与 Task 3 的同名助手一致：收藏测的是用户态，不是建树，所以直接插行造数据。 */
    private Long insertNode(Long libraryId, String name, int directPageCount) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', ?, 'ACTIVE')
                RETURNING id
                """, Long.class, libraryId, name, name, name, directPageCount);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
        nodeId = insertNode(library.getId(), "夏日画集", 24);

        username = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = registrationService.register(username, "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    @Test
    void addsAndRemovesAFavorite() {
        favoriteService.add(userId, nodeId);
        assertThat(favoriteService.isFavorite(userId, nodeId)).isTrue();

        favoriteService.remove(userId, nodeId);
        assertThat(favoriteService.isFavorite(userId, nodeId)).isFalse();
    }

    @Test
    void addingTwiceIsIdempotent() {
        favoriteService.add(userId, nodeId);
        favoriteService.add(userId, nodeId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM image_favorite WHERE user_id = ? AND image_node_id = ?",
                Integer.class, userId, nodeId)).isEqualTo(1);
    }

    @Test
    void removingSomethingNotFavoritedIsNotAnError() {
        favoriteService.remove(userId, nodeId);

        assertThat(favoriteService.isFavorite(userId, nodeId)).isFalse();
    }

    @Test
    void favoritesAreStrictlyPerUser() {
        UserAccount other = registrationService.register(
                "o" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        accessService.grant(other.getId(), library.getId());

        favoriteService.add(userId, nodeId);

        assertThat(favoriteService.isFavorite(other.getId(), nodeId)).isFalse();
        assertThat(favoriteService.listNodes(other.getId(), 20)).isEmpty();
    }

    @Test
    void listsNewestFirst() throws InterruptedException {
        Long second = insertNode(library.getId(), "冬日画集", 12);
        favoriteService.add(userId, nodeId);
        Thread.sleep(10);
        favoriteService.add(userId, second);

        assertThat(favoriteService.listNodes(userId, 20))
                .extracting(ImageNode::getId)
                .containsExactly(second, nodeId);
    }

    @Test
    void deletingANodeRemovesItFromEveryonesFavorites() {
        favoriteService.add(userId, nodeId);

        jdbc.update("DELETE FROM image_node WHERE id = ?", nodeId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM image_favorite WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    void endpointsToggleAndList() throws Exception {
        mockMvc.perform(put("/api/image/nodes/{id}/favorite", nodeId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/favorites").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("夏日画集"));

        mockMvc.perform(delete("/api/image/nodes/{id}/favorite", nodeId)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/image/favorites").with(httpBasic(username, "pw")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void cannotFavoriteANodeInAnInaccessibleLibrary() throws Exception {
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertNode(other.getId(), "看不见的画集", 3);

        mockMvc.perform(put("/api/image/nodes/{id}/favorite", hidden)
                        .with(httpBasic(username, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsFavoritingAPlainFolderNotJustAReadableBook() {
        // spec §6.5 明写「允许收藏任意节点，包括文件夹」：
        // directPageCount = 0 的节点 isReadable() 为 false，但照样可以被收藏
        Long folderId = insertNode(library.getId(), "某画师", 0);

        favoriteService.add(userId, folderId);

        assertThat(favoriteService.listNodes(userId, 20))
                .extracting(ImageNode::getId)
                .contains(folderId);
    }

    @Test
    void 库访问权被撤销后收藏列表不再返回该条目() throws Exception {
        favoriteService.add(userId, nodeId);

        accessService.revoke(userId, library.getId());

        mockMvc.perform(get("/api/image/favorites").with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
