package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;

import java.util.List;
import java.util.Optional;

/**
 * 元数据提供者。实现它并注册为 Spring bean 即可加入刮削链。
 *
 * <p>分成 {@code search} 与 {@code fetch} 两步而不是一步到位：搜索结果只够
 * 判断"像不像"，详情往往要再发一次请求。分开之后中等置信度的候选可以只存搜索
 * 结果、等用户确认时再取详情，<b>省掉一次注定要丢弃的详情请求</b>。
 *
 * <p>实现约定：
 * <ul>
 *   <li>找不到返回空列表，<b>不要抛异常</b>——没找到是正常状态。</li>
 *   <li>网络故障或被限流抛 {@link ProviderUnavailableException}。</li>
 *   <li>{@code score} 自己算（本项目用 {@code TitleSimilarity} 的二元组 Dice 系数）。</li>
 * </ul>
 */
public interface MetadataProvider {

    /** 提供者名，与 {@code libraries.metadata_providers} 里的字符串对应。 */
    String name();

    boolean supports(LibraryDomain domain);

    /**
     * 当前是否可用。默认可用；需要 API key 的提供者在缺 key 时返回 {@code false}，
     * 链会安静跳过它——<b>这不算错误</b>（spec 13 的风险缓解项）。
     */
    default boolean available() {
        return true;
    }

    List<MetadataCandidate> search(ScrapeSubject subject);

    /** 取详情。候选已失效（对方删了条目）时返回空。 */
    Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate);
}
