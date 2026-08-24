package com.mymedia.metadata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 标题相似度：字符二元组的 Dice 系数 {@code 2 × |A ∩ B| / (|A| + |B|)}。
 *
 * <p><b>为什么不是编辑距离</b>：编辑距离在中文上很不稳。"进击的巨人"与"巨人"
 * 编辑距离是 3，看起来很远，实际是包含关系；二元组重叠直接反映共有的字组。
 *
 * <p><b>为什么是二元组而不是三元组</b>：多数中文词是两个字，二元组的信息密度更合适。
 * 顺带一提，搜索走的是数据库端 {@code pg_trgm} 的三元组索引——同一个思路的两种落点，
 * 是一组现成的对照讲解素材。
 *
 * <p>交集按<b>多重集</b>算：{@code "AAAA"} 与 {@code "AA"} 若按集合算会得到 1.0，
 * 显然不对。
 */
final class TitleSimilarity {

    private TitleSimilarity() {
    }

    static double between(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.length() == 1 || b.length() == 1) {
            // 一个字切不出二元组，退化成相等比较
            return a.equals(b) ? 1.0 : 0.0;
        }

        Map<String, Integer> aGrams = bigrams(a);
        Map<String, Integer> bGrams = bigrams(b);

        int intersection = 0;
        for (Map.Entry<String, Integer> entry : aGrams.entrySet()) {
            intersection += Math.min(entry.getValue(), bGrams.getOrDefault(entry.getKey(), 0));
        }
        return 2.0 * intersection / (a.length() - 1 + b.length() - 1);
    }

    /** 去掉空白与标点、统一小写——它们不携带辨识信息，只会稀释系数。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(codePoint);
            }
        });
        return normalized.toString();
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i < value.length() - 1; i++) {
            grams.merge(value.substring(i, i + 2), 1, Integer::sum);
        }
        return grams;
    }
}
