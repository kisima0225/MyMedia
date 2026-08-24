package com.mymedia.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 刮削相关配置。
 *
 * @param userAgent          外部请求必须带的标识性 UA——匿名爬对方是给自己招风控
 * @param minRequestInterval 客户端侧限流：同一个提供者两次请求之间的最小间隔
 * @param requestTimeout     外部请求的连接与读取超时时间
 * @param autoApplyThreshold 相似度达到它就自动应用
 * @param reviewThreshold    相似度达到它就进待确认队列，低于它直接丢弃
 */
@ConfigurationProperties(prefix = "mymedia.metadata")
record MetadataProperties(
        String userAgent,
        Duration minRequestInterval,
        double autoApplyThreshold,
        double reviewThreshold,
        Bangumi bangumi,
        Tmdb tmdb,
        Duration requestTimeout) {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    MetadataProperties(String userAgent, Duration minRequestInterval,
                       double autoApplyThreshold, double reviewThreshold,
                       Bangumi bangumi, Tmdb tmdb) {
        this(userAgent, minRequestInterval, autoApplyThreshold, reviewThreshold,
                bangumi, tmdb, null);
    }

    record Bangumi(String baseUrl) {
        Bangumi {
            baseUrl = baseUrl == null ? "https://api.bgm.tv" : baseUrl;
        }
    }

    record Tmdb(String baseUrl, String apiKey, String language) {
        Tmdb {
            baseUrl = baseUrl == null ? "https://api.themoviedb.org/3" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            language = language == null ? "zh-CN" : language;
        }
    }

    MetadataProperties {
        userAgent = userAgent == null || userAgent.isBlank()
                ? "MyMedia/0.1 (self-hosted media library)" : userAgent;
        minRequestInterval = minRequestInterval == null ? Duration.ofSeconds(1) : minRequestInterval;
        autoApplyThreshold = autoApplyThreshold <= 0 ? 0.8 : autoApplyThreshold;
        reviewThreshold = reviewThreshold <= 0 ? 0.4 : reviewThreshold;
        bangumi = bangumi == null ? new Bangumi(null) : bangumi;
        tmdb = tmdb == null ? new Tmdb(null, null, null) : tmdb;
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }
}
