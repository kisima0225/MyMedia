package com.mymedia.shared;

import java.util.Locale;

/**
 * 一次搜索输入的规范化结果。
 *
 * <p>存在的理由有两个，都不是"整洁"：
 * <ol>
 *   <li><b>转义。</b> {@code %}、{@code _}、{@code \} 在 LIKE 里是元字符。
 *       用户搜 {@code 50%} 若原样拼进模式，会变成"以 50 开头的任意串"——
 *       既是错误结果，也是"用户输入不能直接拼进模式"这条纪律的反例。</li>
 *   <li><b>大小写。</b> 匹配用 {@code ILIKE}（本来就不分大小写），
 *       但排序用的 {@code similarity()} <b>是</b>大小写敏感的：
 *       {@code Big} 与 {@code big} 切出来的三元组不同。所以另留一个小写副本给打分用。</li>
 * </ol>
 *
 * <p>纯逻辑、无依赖，因此它的测试是纯单元测试。
 */
public record SearchQuery(String normalized, String lowered, String likePattern) {

    /** 少于这个码点数时，GIN trgm 索引提取不出三元组（实测，见 ADR-006）。 */
    private static final int TRIGRAM_MIN_LENGTH = 3;

    public static SearchQuery of(String raw) {
        if (raw == null || raw.isBlank()) {
            // 放行空查询会得到 '%%' 模式，等于把整个库倒出来
            throw new IllegalArgumentException("搜索词不能为空");
        }
        String normalized = raw.trim().replaceAll("\\s+", " ");
        return new SearchQuery(normalized,
                normalized.toLowerCase(Locale.ROOT),
                "%" + escapeLike(normalized) + "%");
    }

    /**
     * 这次查询能不能用上三元组索引。
     *
     * <p><b>不改变行为，只用于日志与讲解</b>：少于 3 个码点时索引不提供任何过滤，
     * 查询退化成全表扫描 + recheck（10 万行实测 29ms，与顺序扫描持平）。
     * 而两个字恰恰是中文最常见的查询长度——这个事实值得被记录下来，
     * 而不是等到某天有人问"为什么搜两个字慢"时再去猜。
     */
    public boolean usesTrigramIndex() {
        return normalized.codePointCount(0, normalized.length()) >= TRIGRAM_MIN_LENGTH;
    }

    /** 反斜杠必须最先转义，否则后面转出来的反斜杠会被再转一次。 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
