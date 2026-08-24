package com.mymedia.metadata;

import com.mymedia.shared.MetadataPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供者链的编排。
 *
 * <pre>
 * LocalNfo  →  配置的外部刮削器（按 libraries.metadata_providers 的顺序）  →  Filename 兜底
 * </pre>
 *
 * <p><b>链的顺序就是 spec 7.2 的优先级</b>，不需要第二套 tier 比较：
 * <ul>
 *   <li>{@code LocalNfo} 永远排最前，不必写进配置——规则 2 明确它优先于刮削。
 *       规则 3「空数组即不刮削」由任务层兑现：那样的库根本不排任务，
 *       LocalNfo 也就不会跑。</li>
 *   <li>高分（≥ {@code autoApplyThreshold}）<b>命中即停</b>，后面的提供者不再问。</li>
 *   <li>{@code Filename} 永远排最后，且它的结果<b>不算命中</b>：
 *       应用的同时置 {@code NO_MATCH}，界面安静回落。</li>
 * </ul>
 *
 * <p>一个提供者抛 {@link ProviderUnavailableException} 不会中断整条链：先记下来接着问
 * 后面的。只有<b>什么都没得到且确实发生过故障</b>时才判 {@code ERROR} 让任务重试——
 * 否则一个限流中的 TMDB 会连带毁掉本来能命中的 Bangumi。
 */
@Component
class MetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataResolver.class);

    private final Map<String, MetadataProvider> providersByName;
    private final MetadataProperties properties;

    MetadataResolver(List<MetadataProvider> providers, MetadataProperties properties) {
        Map<String, MetadataProvider> byName = new LinkedHashMap<>();
        providers.forEach(provider -> byName.put(provider.name(), provider));
        this.providersByName = byName;
        this.properties = properties;
    }

    /**
     * 归一化提供者链的可重试故障。
     *
     * <p>{@link MetadataProvider} 的 {@code search}/{@code fetch} 必须把网络、超时和限流
     * 表达为 {@link ProviderUnavailableException}；这里会吸收它并继续尝试后续提供者，
     * 在没有其他结果时返回 {@link ResolutionResult#error()}。提供者发现阶段逸出的同类
     * 异常交给任务处理器做最后的状态归一化。
     */
    ResolutionResult resolve(ScrapeSubject subject, List<String> configuredProviders) {
        List<MetadataCandidate> reviewable = new ArrayList<>();
        boolean sawFailure = false;

        for (MetadataProvider provider : chainFor(subject, configuredProviders)) {
            List<MetadataCandidate> candidates;
            try {
                candidates = provider.search(subject);
            } catch (ProviderUnavailableException e) {
                log.warn("提供者 {} 暂时不可用：{}", provider.name(), e.getMessage());
                sawFailure = true;
                continue;
            }

            Optional<MetadataCandidate> best = candidates.stream()
                    .max(Comparator.comparingDouble(MetadataCandidate::score));
            if (best.isEmpty()) {
                continue;
            }

            MetadataCandidate candidate = best.get();
            if (candidate.score() >= properties.autoApplyThreshold()) {
                try {
                    Optional<MetadataPatch> patch = provider.fetch(subject, candidate);
                    if (patch.isPresent()) {
                        return ResolutionResult.matched(patch.get());
                    }
                } catch (ProviderUnavailableException e) {
                    log.warn("提供者 {} 取详情失败：{}", provider.name(), e.getMessage());
                    sawFailure = true;
                }
                continue;
            }

            if (candidate.score() >= properties.reviewThreshold()) {
                // 绝不在低置信度下强行写入：攒起来交给用户确认
                candidates.stream()
                        .filter(each -> each.score() >= properties.reviewThreshold())
                        .forEach(reviewable::add);
            }
        }

        if (!reviewable.isEmpty()) {
            return ResolutionResult.needsReview(reviewable);
        }
        if (sawFailure) {
            // 什么都没拿到，而且确实有提供者报了故障 —— 值得重试
            return ResolutionResult.error();
        }
        return ResolutionResult.noMatch(fallback(subject));
    }

    /** 构造这次要问的提供者序列：LocalNfo 打头，配置的刮削器居中，Filename 不在其中。 */
    private List<MetadataProvider> chainFor(ScrapeSubject subject, List<String> configuredProviders) {
        List<MetadataProvider> chain = new ArrayList<>();
        addIfUsable(chain, providersByName.get(LocalNfoProvider.NAME), subject);
        for (String name : configuredProviders) {
            if (LocalNfoProvider.NAME.equals(name) || FilenameProvider.NAME.equals(name)) {
                continue;   // 这两个的位置是固定的，配置里写了也不改变顺序
            }
            MetadataProvider provider = providersByName.get(name);
            if (provider == null) {
                log.warn("配置里的刮削器不存在，已忽略: {}", name);
                continue;
            }
            addIfUsable(chain, provider, subject);
        }
        return chain;
    }

    private void addIfUsable(List<MetadataProvider> chain, MetadataProvider provider,
                             ScrapeSubject subject) {
        if (provider == null) {
            return;
        }
        if (!provider.supports(subject.domain())) {
            return;
        }
        if (!provider.available()) {
            // 缺 API key 之类：安静跳过，不算错误
            log.debug("提供者 {} 当前不可用，已跳过", provider.name());
            return;
        }
        chain.add(provider);
    }

    private MetadataPatch fallback(ScrapeSubject subject) {
        MetadataProvider filename = providersByName.get(FilenameProvider.NAME);
        if (filename == null) {
            return null;
        }
        return filename.search(subject).stream().findFirst()
                .flatMap(candidate -> filename.fetch(subject, candidate))
                .orElse(null);
    }
}
