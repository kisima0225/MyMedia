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
 * TMDB 提供者：只管视频域。
 *
 * <p><b>缺 API key 时 {@link #available()} 返回 false，链安静跳过它，不算错误。</b>
 * 这条路径是必须保住的——别人克隆这个仓库时没有 key，演示不能因此崩掉
 * （演示数据本来也靠本地 NFO，见 {@code LocalNfoProvider}）。
 *
 * <p><b>诚实声明</b>：本机没有 TMDB key，端点与字段名按官方文档写、由本地桩服务器
 * 覆盖，<b>没有真机验证</b>。讲解文档里要照实说，不要声称"对接并验证了 TMDB"。
 */
@Component
class TmdbProvider implements MetadataProvider {

    static final String NAME = "TMDB";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpProviderSupport http;
    private final MetadataProperties properties;
    private final RestClient client;

    TmdbProvider(HttpProviderSupport http, MetadataProperties properties) {
        this.http = http;
        this.properties = properties;
        this.client = http.client(properties.tmdb().baseUrl());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        // 影视归 TMDB，漫画与图集归 Bangumi
        return domain == LibraryDomain.VIDEO;
    }

    @Override
    public boolean available() {
        return !properties.tmdb().apiKey().isBlank();
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.SEARCH_CACHE,
               key = "'tmdb:' + #subject.title() + ':' + #subject.year()")
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        Optional<String> response = subject.year() == null
                ? http.get(NAME, client, "/search/movie?api_key={key}&language={lang}&query={query}",
                        properties.tmdb().apiKey(), properties.tmdb().language(), subject.title())
                : http.get(NAME, client,
                        "/search/movie?api_key={key}&language={lang}&query={query}&year={year}",
                        properties.tmdb().apiKey(), properties.tmdb().language(),
                        subject.title(), subject.year());
        if (response.isEmpty()) {
            return List.of();
        }

        JsonNode root = parse(response.get());
        List<MetadataCandidate> candidates = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String title = item.path("title").asString(null);
            String originalTitle = item.path("original_title").asString(null);
            double score = Math.max(
                    TitleSimilarity.between(subject.title(), title),
                    TitleSimilarity.between(subject.title(), originalTitle));
            candidates.add(new MetadataCandidate(NAME,
                    item.path("id").asString(null), title,
                    yearOf(item.path("release_date").asString(null)),
                    score, item.toString()));
        }
        return candidates;
    }

    @Override
    @Cacheable(cacheNames = ProviderCacheConfig.DETAIL_CACHE,
               key = "'tmdb:' + #candidate.externalId()")
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<String> response = http.get(NAME, client, "/movie/{id}?api_key={key}&language={lang}",
                candidate.externalId(), properties.tmdb().apiKey(), properties.tmdb().language());
        if (response.isEmpty()) {
            return Optional.empty();
        }

        JsonNode item = parse(response.get());
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, MetadataFields.TITLE, item.path("title").asString(null));
        putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, item.path("original_title").asString(null));
        putIfPresent(fields, MetadataFields.SUMMARY, item.path("overview").asString(null));
        putIfPresent(fields, MetadataFields.RELEASE_DATE, item.path("release_date").asString(null));
        putIfPresent(fields, MetadataFields.RATING, item.path("vote_average").asString(null));

        Map<String, String> extras = new LinkedHashMap<>();
        JsonNode companies = item.path("production_companies");
        if (companies.isArray() && !companies.isEmpty()) {
            putIfPresent(extras, "studio", companies.path(0).path("name").asString(null));
        }

        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, candidate.externalId(),
                fields, extras, response.get()));
    }

    private static Integer yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(releaseDate.substring(0, 4));
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

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
