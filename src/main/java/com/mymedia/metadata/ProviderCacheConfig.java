package com.mymedia.metadata;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 刮削结果缓存。
 *
 * <p>用 Spring 自带的 {@link ConcurrentMapCacheManager}，<b>不引 Redis 也不引 Caffeine</b>：
 * 单实例部署、没有跨节点缓存需求，引入一个无法解释的中间件在面试里是负分。
 *
 * <p>它没有淘汰策略，这是可以接受的：缓存键是 (提供者, 标题, 年份)，
 * 条目数上界就是媒体库的条目数——一个一万条目的库也就一万个几百字节的条目。
 * 真正的收益是一轮扫描里同名条目（同一部剧的多集、同一画师的多个合集）
 * 只查一次。
 */
@Configuration
@EnableCaching
class ProviderCacheConfig {

    static final String SEARCH_CACHE = "metadataSearch";
    static final String DETAIL_CACHE = "metadataDetail";

    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(SEARCH_CACHE, DETAIL_CACHE);
    }
}
