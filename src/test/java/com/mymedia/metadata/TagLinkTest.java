package com.mymedia.metadata;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TagLinkTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TagService tagService;

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
        accessService.grant(user.getId(), library.getId());
    }

    private Long tagId(String name) {
        return tagService.findOrCreate(LibraryDomain.VIDEO, name).getId();
    }

    @Test
    void setTagsReplacesTheWholeSetRatherThanAppending() {
        Long action = tagId("动作" + UUID.randomUUID());
        Long war = tagId("战争" + UUID.randomUUID());
        Long drama = tagId("剧情" + UUID.randomUUID());

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action, war));
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(drama));

        assertThat(tagService.tagsOf(LibraryDomain.VIDEO, itemId))
                .extracting(Tag::getId)
                .containsExactly(drama);
    }

    @Test
    void settingTheSameTagTwiceIsIdempotent() {
        Long action = tagId("动作" + UUID.randomUUID());

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action, action));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE video_item_id = ?",
                Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void anEmptyListClearsAllTags() {
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(tagId("动作" + UUID.randomUUID())));

        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of());

        assertThat(tagService.tagsOf(LibraryDomain.VIDEO, itemId)).isEmpty();
    }

    @Test
    void listsTargetsCarryingATag() {
        Long action = tagId("动作" + UUID.randomUUID());
        Long secondItem = insertItem(library.getId(), "雪原突击");
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));
        tagService.setTags(LibraryDomain.VIDEO, secondItem, List.of(action));

        assertThat(tagService.targetIdsWithTag(action, 20))
                .containsExactlyInAnyOrder(itemId, secondItem);
    }

    @Test
    void deletingATagUnlinksItEverywhere() {
        Long action = tagId("动作" + UUID.randomUUID());
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        tagService.delete(action);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE video_item_id = ?",
                Integer.class, itemId)).isZero();
    }

    @Test
    void deletingAnItemUnlinksItsTags() {
        Long action = tagId("动作" + UUID.randomUUID());
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item_tag WHERE tag_id = ?",
                Integer.class, action)).isZero();
    }

    @Test
    void endpointReplacesTagsAndReadsThemBack() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());

        mockMvc.perform(put("/api/video/items/{id}/tags", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + action + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));

        mockMvc.perform(get("/api/video/items/{id}/tags", itemId).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(action));
    }

    @Test
    void tagBrowseEndpointHidesTargetsTheUserCannotAccess() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));
        tagService.setTags(LibraryDomain.VIDEO, hidden, List.of(action));

        mockMvc.perform(get("/api/tags/{id}/items", action).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("沙漠风暴"));
    }

    @Test
    void taggingAnItemInAnInaccessibleLibraryIsNotFound() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        Long hidden = insertItem(other.getId(), "看不见的片子");

        mockMvc.perform(put("/api/video/items/{id}/tags", hidden)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + action + "]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTagFromTheOtherDomainIsRejectedBeforeReachingTheDatabase() throws Exception {
        Long imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, "画集" + UUID.randomUUID()).getId();

        // 数据库那道复合外键是最后一道防线；服务层应当先给出一个能读懂的错误
        mockMvc.perform(put("/api/video/items/{id}/tags", itemId)
                        .with(httpBasic(username, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + imageTag + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taggedTargetCarriesTheCoverSoTheCardCanBeDrawn() throws Exception {
        Long action = tagId("动作" + UUID.randomUUID());
        // derived_asset.source_scanned_file_id 在落地的 V10 里是 NOT NULL（非计划文本假设的可空），
        // 先插一行 scanned_file 再引用它
        Long scannedFileId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'cover-source-' || gen_random_uuid() || '.jpg', 1024, now(), 'jpg')
                RETURNING id
                """, Long.class, library.getId());
        Long assetId = jdbc.queryForObject("""
                INSERT INTO derived_asset (kind, source_scanned_file_id, relative_path, size_bytes)
                VALUES ('COVER', ?, 'covers/test-' || gen_random_uuid() || '.jpg', 1024)
                RETURNING id
                """, Long.class, scannedFileId);
        jdbc.update("UPDATE video_item SET cover_asset_id = ? WHERE id = ?", assetId, itemId);
        tagService.setTags(LibraryDomain.VIDEO, itemId, List.of(action));

        mockMvc.perform(get("/api/tags/{id}/items", action).with(httpBasic(username, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].coverAssetId").value(assetId));
    }
}
