package com.mymedia.shared;

import java.util.Locale;

/**
 * 把名称转换成可用<b>字典序</b>直接比较的键，使其中的数字按数值大小排序。
 *
 * <p>不加处理时字典序会把 {@code 第1卷, 第2卷, 第10卷} 排成 {@code 1, 10, 2}。
 * 这是媒体库必踩的坑，视频域的集号与图片域的卷号都依赖它。
 *
 * <p>算法：把每一段连续数字替换成 {@code 长度位数 + 长度 + 数值}，
 * 例如 {@code 10} → {@code 2:10}、{@code 2} → {@code 1:2}。
 * 因为 {@code "1:"} 字典序小于 {@code "2:"}，位数少的数字自然排在前面；
 * 位数相同则退化为按数值逐位比较。
 *
 * <p>全程纯字符串操作，<b>不解析成 long</b>——20 位以上的数字会溢出。
 *
 * <p>键在写入时预计算并存进 {@code sort_key} 列，查询时直接 ORDER BY，
 * 不在每次查询时重新计算。
 */
public final class NaturalSortKey {

    private NaturalSortKey() {
    }

    public static String of(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(lower.length() + 8);

        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (!isAsciiDigit(c)) {
                key.append(c);
                i++;
                continue;
            }

            int start = i;
            while (i < lower.length() && isAsciiDigit(lower.charAt(i))) {
                i++;
            }
            String digits = lower.substring(start, i);

            // 去掉前导零，使 001 与 1 得到相同的键
            int firstSignificant = 0;
            while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
                firstSignificant++;
            }
            String normalized = digits.substring(firstSignificant);

            // 位数前缀本身也可能多位（如 100 位的数字），再套一层长度标记
            String lengthMarker = String.valueOf(normalized.length());
            key.append(lengthMarker.length()).append(':').append(lengthMarker).append(':')
                    .append(normalized);
        }
        return key.toString();
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
