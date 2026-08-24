package com.mymedia.shared;

import java.util.Set;

/**
 * 标准元数据字段名。
 *
 * <p>这些字符串同时是三个地方的键：{@code MetadataPatch.fields} 的键、
 * {@code field_sources} jsonb 的键、{@code locked_fields} 数组的元素。
 * 定成常量而不是各处写字面量——拼错一个字母就会让锁定失效，而那是静默失败。
 */
public final class MetadataFields {

    public static final String TITLE = "title";
    public static final String ORIGINAL_TITLE = "originalTitle";
    public static final String SUMMARY = "summary";

    /** ISO 日期，形如 {@code 2019-05-01}。 */
    public static final String RELEASE_DATE = "releaseDate";

    /** 十进制小数，形如 {@code 8.4}。 */
    public static final String RATING = "rating";

    /** 标准字段全集。不在其中的键会落进 {@code metadata} jsonb。 */
    public static final Set<String> STANDARD =
            Set.of(TITLE, ORIGINAL_TITLE, SUMMARY, RELEASE_DATE, RATING);

    private MetadataFields() {
    }
}
