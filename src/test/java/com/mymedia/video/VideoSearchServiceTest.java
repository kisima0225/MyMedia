package com.mymedia.video;

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

class VideoSearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    VideoSearchService searchService;

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

    /** 搜索测的是 SQL，不是扫描链路，所以直接插行造数据。 */
    private Long insertItem(Long libraryId, String title, String originalTitle, String summary) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, original_title, summary)
                VALUES (?, 'MOVIE', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, libraryId, title, title, originalTitle, summary);
    }

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
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

    private List<String> titles(String q) {
        return searchService.search(userId, SearchQuery.of(q), 20).stream()
                .map(VideoSearchHit::title)
                .toList();
    }

    @Test
    void findsChineseByTwoCharacterSubstring() {
        insertItem(library.getId(), "进击的巨人", null, null);
        insertItem(library.getId(), "夏目友人帐", null, null);

        // 这是整条搜索链路存在的理由：相似度只有 0.125，% 操作符匹配不到，
        // 必须走 ILIKE 子串
        assertThat(titles("巨人")).containsExactly("进击的巨人");
    }

    @Test
    void findsBySingleCharacterToo() {
        insertItem(library.getId(), "进击的巨人", null, null);

        assertThat(titles("巨")).containsExactly("进击的巨人");
    }

    @Test
    void findsLatinByStemmingWhichSubstringMatchingWouldMiss() {
        insertItem(library.getId(), "The Bunnies Are Running", null, null);

        // ILIKE '%bunny%' 匹配不到 Bunnies，tsvector 路径能
        assertThat(titles("bunny")).containsExactly("The Bunnies Are Running");
    }

    @Test
    void substringHitsOutrankFtsOnlyHits() {
        insertItem(library.getId(), "The Bunnies Are Running", null, null);
        insertItem(library.getId(), "Bunny Hop", null, null);

        // 打出 bunny 的人期待的是标题里真有 bunny 的那个
        assertThat(titles("bunny")).containsExactly("Bunny Hop", "The Bunnies Are Running");
    }

    @Test
    void ranksCloserTitlesFirstAmongSubstringHits() {
        insertItem(library.getId(), "进击的巨人 最终季 完结篇 特别版", null, null);
        insertItem(library.getId(), "进击的巨人", null, null);

        assertThat(titles("进击的巨人").get(0)).isEqualTo("进击的巨人");
    }

    @Test
    void searchesOriginalTitleAsWell() {
        insertItem(library.getId(), "大雄兔", "Big Buck Bunny", null);

        assertThat(titles("Buck")).containsExactly("大雄兔");
    }

    @Test
    void searchesSummaryThroughTheFtsPathOnly() {
        insertItem(library.getId(), "无名短片", null, "A rabbit and three rodents");

        assertThat(titles("rodents")).containsExactly("无名短片");
    }

    @Test
    void neverReturnsItemsFromLibrariesTheUserCannotAccess() {
        MediaLibrary other = newLibrary();
        insertItem(other.getId(), "进击的巨人", null, null);
        insertItem(library.getId(), "巨人族的新娘", null, null);

        assertThat(titles("巨人")).containsExactly("巨人族的新娘");
    }

    @Test
    void userWithNoAccessibleLibrariesGetsEmptyResultNotAnError() {
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        insertItem(library.getId(), "进击的巨人", null, null);

        // IN () 是语法错误，必须在进 SQL 之前就短路
        assertThat(searchService.search(stranger.getId(), SearchQuery.of("巨人"), 20)).isEmpty();
    }

    @Test
    void treatsPercentAsALiteralNotAWildcard() {
        insertItem(library.getId(), "折扣 50% 纪录片", null, null);
        insertItem(library.getId(), "折扣年代", null, null);

        assertThat(titles("50%")).containsExactly("折扣 50% 纪录片");
    }

    @Test
    void respectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            insertItem(library.getId(), "巨人系列 " + i, null, null);
        }

        assertThat(searchService.search(userId, SearchQuery.of("巨人"), 3)).hasSize(3);
    }

    @Test
    void adminSeesEveryLibraryWithoutExplicitGrants() {
        UserAccount admin = registrationService.register(
                "a" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.ADMIN);
        MediaLibrary other = newLibrary();
        insertItem(other.getId(), "只有管理员能看到的巨人", null, null);

        assertThat(searchService.search(admin.getId(), SearchQuery.of("巨人"), 20))
                .extracting(VideoSearchHit::title)
                .contains("只有管理员能看到的巨人");
    }
}
