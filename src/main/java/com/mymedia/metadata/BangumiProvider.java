package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bangumi（bgm.tv）提供者：番剧走视频域，漫画走图片域。
 *
 * <p>协议事实全部实测过（见本任务的说明表）：无需鉴权；搜索是
 * {@code POST /v0/search/subjects}；<b>搜索结果里没有 summary</b>，
 * 简介只在详情里有——这正是 SPI 把 {@code search} 与 {@code fetch} 分成两步的现实依据。
 */
@Component
class BangumiProvider implements MetadataProvider {

    static final String NAME = "Bangumi";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SEARCH_LIMIT = 10;

    /** Bangumi 的条目类型：1=书籍 2=动画 3=音乐 4=游戏 6=三次元（实测）。 */
    private static final int TYPE_BOOK = 1;
    private static final int TYPE_ANIME = 2;

    private final HttpProviderSupport http;
    private final RestClient client;

    BangumiProvider(HttpProviderSupport http, MetadataProperties properties) {
        this.http = http;
        this.client = http.client(properties.bangumi().baseUrl());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return true;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.SEARCH_CACHE,
               key = "'bangumi:' + #subject.domain() + ':' + #subject.title()")
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        int type = subject.domain() == LibraryDomain.IMAGE ? TYPE_BOOK : TYPE_ANIME;
        String body = "{\"keyword\":" + quote(subject.title())
                + ",\"filter\":{\"type\":[" + type + "]}}";

        Optional<String> response = http.postJson(NAME, client,
                "/v0/search/subjects?limit={limit}", body, SEARCH_LIMIT);
        if (response.isEmpty()) {
            return List.of();
        }

        JsonNode root = parse(response.get());
        List<MetadataCandidate> candidates = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            candidates.add(toCandidate(subject, item));
        }
        return candidates;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.DETAIL_CACHE,
               key = "'bangumi:' + #candidate.externalId()")
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<String> response = http.get(NAME, client,
                "/v0/subjects/{id}", candidate.externalId());
        if (response.isEmpty()) {
            return Optional.empty();
        }

        JsonNode item = parse(response.get());
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, MetadataFields.TITLE, displayName(item));
        putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, item.path("name").asString(null));
        putIfPresent(fields, MetadataFields.SUMMARY, item.path("summary").asString(null));
        putIfPresent(fields, MetadataFields.RELEASE_DATE, item.path("date").asString(null));
        putIfPresent(fields, MetadataFields.RATING, item.path("rating").path("score").asString(null));

        Map<String, String> extras = new LinkedHashMap<>();
        putIfPresent(extras, "platform", item.path("platform").asString(null));
        putIfPresent(extras, "totalEpisodes", item.path("total_episodes").asString(null));
        putIfPresent(extras, "volumes", item.path("volumes").asString(null));

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, candidate.externalId(),
                fields, extras, response.get()));
    }

    private MetadataCandidate toCandidate(ScrapeSubject subject, JsonNode item) {
        String chinese = item.path("name_cn").asString(null);
        String original = item.path("name").asString(null);
        // 中译名与原名都比一遍取较高者——文件名两种写法都常见
        double score = Math.max(
                TitleSimilarity.between(subject.title(), chinese),
                TitleSimilarity.between(subject.title(), original));

        return new MetadataCandidate(NAME,
                item.path("id").asString(null),
                displayName(item),
                yearOf(item.path("date").asString(null)),
                score,
                item.toString());
    }

    /** 有中译名用中译名，没有就用原名。 */
    private static String displayName(JsonNode item) {
        String chinese = item.path("name_cn").asString(null);
        return chinese == null || chinese.isBlank() ? item.path("name").asString(null) : chinese;
    }

    private static Integer yearOf(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ProviderUnavailableException(NAME + " 返回了无法解析的响应", e);
        }
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法编码搜索关键词", e);
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
