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

class TmdbProviderTest {

    private static final String SEARCH_BODY = """
            {
              "page": 1,
              "results": [
                {"id": 10378, "title": "大雄兔", "original_title": "Big Buck Bunny",
                 "release_date": "2008-05-20", "vote_average": 7.9,
                 "overview": "一只巨兔与三个坏蛋的故事。"}
              ]
            }
            """;

    private static final String DETAIL_BODY = """
            {
              "id": 10378, "title": "大雄兔", "original_title": "Big Buck Bunny",
              "release_date": "2008-05-20", "vote_average": 7.9,
              "overview": "一只巨兔与三个坏蛋的故事。",
              "production_companies": [{"name": "Blender Foundation"}]
            }
            """;

    private StubHttpServer server;

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "大雄兔", 2008, null);

    private TmdbProvider providerWithKey(String apiKey) {
        MetadataProperties properties = new MetadataProperties(
                "MyMediaTest/0.1", Duration.ZERO, 0.8, 0.4, null,
                new MetadataProperties.Tmdb(server.baseUrl(), apiKey, "zh-CN"));
        return new TmdbProvider(new HttpProviderSupport(properties), properties);
    }

    @BeforeEach
    void startServer() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void reportsUnavailableWhenNoApiKeyIsConfigured() {
        // 别人克隆这个仓库时没有 key，链必须安静跳过而不是报错（spec 13 的风险缓解项）
        assertThat(providerWithKey("").available()).isFalse();
        assertThat(providerWithKey("   ").available()).isFalse();
        assertThat(providerWithKey("real-key").available()).isTrue();
    }

    @Test
    void onlySupportsVideoLibraries() {
        // TMDB 管影视，漫画与图集归 Bangumi
        assertThat(providerWithKey("k").supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(providerWithKey("k").supports(LibraryDomain.IMAGE)).isFalse();
    }

    @Test
    void searchSendsApiKeyLanguageQueryAndYear() {
        server.respond("/search/movie", 200, SEARCH_BODY);

        providerWithKey("real-key").search(SUBJECT);

        String uri = server.requestedUris().get(0);
        assertThat(uri).startsWith("/search/movie?");
        assertThat(uri).contains("api_key=real-key");
        assertThat(uri).contains("language=zh-CN");
        assertThat(uri).contains("year=2008");
    }

    @Test
    void mapsSearchResultsToScoredCandidates() {
        server.respond("/search/movie", 200, SEARCH_BODY);

        List<MetadataCandidate> candidates = providerWithKey("k").search(SUBJECT);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).externalId()).isEqualTo("10378");
        assertThat(candidates.get(0).year()).isEqualTo(2008);
        assertThat(candidates.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void fetchMapsDetailFieldsIncludingStudio() {
        server.respond("/movie/10378", 200, DETAIL_BODY);

        Optional<MetadataPatch> patch = providerWithKey("k").fetch(SUBJECT,
                new MetadataCandidate(TmdbProvider.NAME, "10378", "大雄兔", 2008, 1.0, "{}"));

        assertThat(patch).isPresent();
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "Big Buck Bunny")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20")
                .containsEntry(MetadataFields.RATING, "7.9");
        assertThat(patch.get().extras()).containsEntry("studio", "Blender Foundation");
    }

    @Test
    void unauthorizedKeyBecomesProviderUnavailable() {
        server.respond("/search/movie", 401, "{\"status_message\":\"Invalid API key\"}");

        // key 配错了要能看出来，而不是静静地一个条目都刮不出来
        assertThatThrownBy(() -> providerWithKey("wrong").search(SUBJECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void emptyResultsAreNotAnError() {
        server.respond("/search/movie", 200, "{\"page\":1,\"results\":[]}");

        assertThat(providerWithKey("k").search(SUBJECT)).isEmpty();
    }
}
