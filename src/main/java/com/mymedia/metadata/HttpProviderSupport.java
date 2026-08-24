package com.mymedia.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 HTTP 调用的公共部分：标识性 UA、客户端侧限流、状态码到语义的映射。
 *
 * <p><b>状态码怎么翻译成语义，是这个类存在的主要理由：</b>
 * <ul>
 *   <li>2xx → 有结果</li>
 *   <li>404 → <b>空</b>。对方删了条目属于"没找到"，是正常状态。</li>
 *   <li>其余（401 / 429 / 5xx / 连不上）→ {@link ProviderUnavailableException}，
 *       条目置 {@code ERROR} 并按退避重试。</li>
 * </ul>
 * 把 404 和 429 混为一谈，会让一个冷门条目在任务表里永远重试下去。
 */
@Component
class HttpProviderSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderSupport.class);

    private final MetadataProperties properties;

    /** 每个提供者一把闸，记录它上一次请求的时刻。 */
    private final Map<String, Object> gates = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallMillis = new ConcurrentHashMap<>();

    HttpProviderSupport(MetadataProperties properties) {
        this.properties = properties;
    }

    RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // 匿名爬对方是给自己招风控；Bangumi 的文档明确要求带标识性 UA
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    Optional<String> get(String provider, RestClient client, String uriTemplate,
                         Object... uriVariables) {
        throttle(provider);
        return interpret(provider, () -> client.get()
                .uri(uriTemplate, uriVariables)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                    // 状态码由下面统一判断，这里只是关掉 RestClient 的默认抛异常行为
                })
                .toEntity(String.class));
    }

    Optional<String> postJson(String provider, RestClient client, String uriTemplate,
                              String body, Object... uriVariables) {
        throttle(provider);
        return interpret(provider, () -> client.post()
                .uri(uriTemplate, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class));
    }

    private Optional<String> interpret(String provider,
                                       java.util.function.Supplier<ResponseEntity<String>> call) {
        ResponseEntity<String> response;
        try {
            response = call.get();
        } catch (RestClientException e) {
            // 连不上、超时、读到一半断了
            throw new ProviderUnavailableException(provider + " 请求失败: " + e.getMessage(), e);
        }

        int status = response.getStatusCode().value();
        if (response.getStatusCode().is2xxSuccessful()) {
            return Optional.ofNullable(response.getBody());
        }
        if (status == 404) {
            // 对方删了条目：这是"没找到"，不是故障
            return Optional.empty();
        }
        throw new ProviderUnavailableException(provider + " 返回 HTTP " + status);
    }

    /**
     * 客户端侧限流：同一提供者两次请求之间至少隔 {@code min-request-interval}。
     *
     * <p>别指望对方的 429 来教你做人——等到被限流时，这一轮扫描的几百个请求
     * 已经发出去了。
     */
    private void throttle(String provider) {
        long minIntervalMillis = properties.minRequestInterval().toMillis();
        if (minIntervalMillis <= 0) {
            return;
        }
        Object gate = gates.computeIfAbsent(provider, key -> new Object());
        synchronized (gate) {
            long now = System.currentTimeMillis();
            long earliest = lastCallMillis.getOrDefault(provider, 0L) + minIntervalMillis;
            if (now < earliest) {
                try {
                    Thread.sleep(earliest - now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderUnavailableException(provider + " 限流等待被中断", e);
                }
            }
            lastCallMillis.put(provider, System.currentTimeMillis());
        }
        log.trace("{} 通过限流闸", provider);
    }
}
