package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImageSearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    ImageSearchService searchService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long userId;

    private Long insertNode(Long libraryId, String name, String status, int directPageCount) {
        return jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        name, sort_key, source_kind, direct_page_count, status)
                VALUES (?, '/' || gen_random_uuid() || '/', '/' || ? || '/', 0,
                        ?, ?, 'DIRECTORY', ?, ?)
                RETURNING id
                """, Long.class, libraryId, name, name, name, directPageCount, status);
    }

    private void setTitle(Long nodeId, String title) {
        jdbc.update("UPDATE image_node SET title = ? WHERE id = ?", title, nodeId);
    }

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/tmp/" + UUID.randomUUID());
    }

    @BeforeEach
    void setUp() {
        library = newLibrary();
        UserAccount user = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        userId = user.getId();
        accessService.grant(userId, library.getId());
    }

    private List<String> names(String q) {
        return searchService.search(userId, SearchQuery.of(q), 20).stream()
                .map(ImageSearchHit::name)
                .toList();
    }

    @Test
    void findsByDirectoryName() {
        insertNode(library.getId(), "某画师 2024 合集", "ACTIVE", 12);
        insertNode(library.getId(), "另一个画师", "ACTIVE", 3);

        assertThat(names("画师 2024")).containsExactly("某画师 2024 合集");
    }

    @Test
    void findsByScrapedTitleWhichTheDirectoryNameDoesNotContain() {
        Long nodeId = insertNode(library.getId(), "[Group] Vol.01 (2019)", "ACTIVE", 180);
        setTitle(nodeId, "进击的巨人");

        // 目录名是发布组的乱码风格，刮削回来的标题才是人认得的那个
        assertThat(names("巨人")).containsExactly("[Group] Vol.01 (2019)");
    }

    @Test
    void hidesNodesWhoseFilesHaveGoneMissing() {
        insertNode(library.getId(), "已下线的巨人画集", "MISSING", 5);
        insertNode(library.getId(), "在线的巨人画集", "ACTIVE", 5);

        // 搜出一个点开就 404 的节点是很差的体验
        assertThat(names("巨人")).containsExactly("在线的巨人画集");
    }

    @Test
    void reportsWhetherTheNodeIsReadable() {
        insertNode(library.getId(), "纯目录 巨人", "ACTIVE", 0);
        insertNode(library.getId(), "可读 巨人", "ACTIVE", 20);

        List<ImageSearchHit> hits = searchService.search(userId, SearchQuery.of("巨人"), 20);

        assertThat(hits).extracting(ImageSearchHit::readable)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void neverReturnsNodesFromLibrariesTheUserCannotAccess() {
        MediaLibrary other = newLibrary();
        insertNode(other.getId(), "别人的巨人", "ACTIVE", 1);
        insertNode(library.getId(), "我的巨人", "ACTIVE", 1);

        assertThat(names("巨人")).containsExactly("我的巨人");
    }

    @Test
    void userWithNoAccessibleLibrariesGetsEmptyResultNotAnError() {
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        insertNode(library.getId(), "巨人", "ACTIVE", 1);

        assertThat(searchService.search(stranger.getId(), SearchQuery.of("巨人"), 20)).isEmpty();
    }

    @Test
    void treatsUnderscoreAsALiteralNotAWildcard() {
        insertNode(library.getId(), "a_b 画集", "ACTIVE", 1);
        insertNode(library.getId(), "axb 画集", "ACTIVE", 1);

        assertThat(names("a_b")).containsExactly("a_b 画集");
    }

    @Test
    void findsLatinByStemming() {
        insertNode(library.getId(), "The Bunnies Collection", "ACTIVE", 30);

        assertThat(names("bunny")).containsExactly("The Bunnies Collection");
    }
}
