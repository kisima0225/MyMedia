package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BangumiProviderTest {

    /** 字段取自实跑 POST /v0/search/subjects 的真实响应形状——注意没有 summary。 */
    private static final String SEARCH_BODY = """
            {
              "total": 2,
              "data": [
                {"id": 55770, "name": "進撃の巨人", "name_cn": "进击的巨人",
                 "date": "2013-04-07", "rating": {"score": 8.4}, "platform": "TV"},
                {"id": 12345, "name": "Natsume Yuujinchou", "name_cn": "夏目友人帐",
                 "date": "2008-07-08", "rating": {"score": 8.8}, "platform": "TV"}
              ]
            }
            """;

    /** 详情才有 summary。 */
    private static final String DETAIL_BODY = """
            {
              "id": 55770, "name": "進撃の巨人", "name_cn": "进击的巨人",
              "date": "2013-04-07", "summary": "人类居住在高墙之内。",
              "rating": {"score": 8.4}, "type": 2, "total_episodes": 25,
              "platform": "TV"
            }
            """;

    private StubHttpServer server;
    private BangumiProvider provider;

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "进击的巨人", 2013, null);

    @BeforeEach
    void startServer() {
        server = StubHttpServer.start();
        MetadataProperties properties = new MetadataProperties(
                "MyMediaTest/0.1", Duration.ZERO, 0.8, 0.4,
                new MetadataProperties.Bangumi(server.baseUrl()), null);
        provider = new BangumiProvider(new HttpProviderSupport(properties), properties);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void searchesByPostingKeywordAndTypeFilter() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        provider.search(SUBJECT);

        assertThat(server.requestedUris().get(0)).startsWith("/v0/search/subjects?limit=");
        assertThat(server.requestBodies().get(0))
                .contains("\"keyword\":\"进击的巨人\"")
                .contains("\"type\":[2]");
    }

    @Test
    void sendsAnIdentifyingUserAgent() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        provider.search(SUBJECT);

        // 匿名爬对方是给自己招风控，Bangumi 的文档明确要求带 UA
        assertThat(server.lastHeader("User-Agent")).isEqualTo("MyMediaTest/0.1");
    }

    @Test
    void scoresCandidatesByTitleSimilarity() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);

        List<MetadataCandidate> candidates = provider.search(SUBJECT);

        assertThat(candidates).hasSize(2);
        MetadataCandidate first = candidates.stream()
                .filter(candidate -> "55770".equals(candidate.externalId())).findFirst().orElseThrow();
        MetadataCandidate second = candidates.stream()
                .filter(candidate -> "12345".equals(candidate.externalId())).findFirst().orElseThrow();
        assertThat(first.score()).isEqualTo(1.0);
        assertThat(second.score()).isLessThan(0.4);
        assertThat(first.title()).isEqualTo("进击的巨人");
        assertThat(first.year()).isEqualTo(2013);
    }

    @Test
    void matchesAgainstOriginalNameToo() {
        server.respond("/v0/search/subjects", 200, SEARCH_BODY);
        ScrapeSubject japanese = new ScrapeSubject(
                LibraryDomain.VIDEO, 1L, 1L, "進撃の巨人", null, null);

        // 日文原名与中译名都要参与比对，取较高者——文件名两种写法都常见
        assertThat(provider.search(japanese).stream()
                .filter(candidate -> "55770".equals(candidate.externalId()))
                .findFirst().orElseThrow().score()).isEqualTo(1.0);
    }

    @Test
    void emptyResultIsAnEmptyListNotAnException() {
        server.respond("/v0/search/subjects", 200, "{\"total\":0,\"data\":[]}");

        assertThat(provider.search(SUBJECT)).isEmpty();
    }

    @Test
    void fetchesSummaryFromTheDetailEndpoint() {
        server.respond("/v0/subjects/55770", 200, DETAIL_BODY);

        Optional<MetadataPatch> patch = provider.fetch(SUBJECT, new MetadataCandidate(
                BangumiProvider.NAME, "55770", "进击的巨人", 2013, 1.0, "{}"));

        assertThat(patch).isPresent();
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "进击的巨人")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "進撃の巨人")
                .containsEntry(MetadataFields.SUMMARY, "人类居住在高墙之内。")
                .containsEntry(MetadataFields.RELEASE_DATE, "2013-04-07")
                .containsEntry(MetadataFields.RATING, "8.4");
        assertThat(patch.get().rawResponse()).contains("total_episodes");
    }

    @Test
    void deletedSubjectIsEmptyRatherThanAnError() {
        // 桩默认对未注册路径返回 404
        assertThat(provider.fetch(SUBJECT, new MetadataCandidate(
                BangumiProvider.NAME, "999999", "没了", null, 1.0, "{}"))).isEmpty();
    }

    @Test
    void rateLimitingResponseBecomesProviderUnavailable() {
        server.respond("/v0/search/subjects", 429, "{\"detail\":\"too many requests\"}");

        // 被限流要重试，不能当成"没找到"
        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void serverErrorBecomesProviderUnavailable() {
        server.respond("/v0/search/subjects", 500, "{}");

        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void connectionRefusedBecomesProviderUnavailable() {
        server.close();

        assertThatThrownBy(() -> provider.search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void usesBookTypeFilterForImageLibraries() {
        server.respond("/v0/search/subjects", 200, "{\"total\":0,\"data\":[]}");

        provider.search(new ScrapeSubject(LibraryDomain.IMAGE, 2L, 1L, "某漫画", null, null));

        // type 1 = 书籍（漫画在 Bangumi 属于书籍）
        assertThat(server.requestBodies().get(0)).contains("\"type\":[1]");
    }

    @Test
    void supportsBothDomainsAndNeedsNoApiKey() {
        assertThat(provider.supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(provider.supports(LibraryDomain.IMAGE)).isTrue();
        assertThat(provider.available()).isTrue();
    }
}
