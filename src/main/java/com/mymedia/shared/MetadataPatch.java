package com.mymedia.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个提供者对某个条目给出的元数据。
 *
 * <p>字段值一律是 {@code String}：提供者的原始数据本来就是文本（NFO 是 XML、
 * 各家 API 是 JSON），保持字符串省掉一层类型协商，类型转换集中在写回的那条 SQL 里
 * （{@code CAST(? AS date)}），格式错误当场报出来而不是在链的中途悄悄丢掉。
 *
 * @param source      提供者名，会写进 {@code field_sources} 与 {@code scrape_source}
 * @param sourceId    该提供者侧的条目 id，写进 {@code scrape_source_id}
 * @param fields      标准字段（键取自 {@link MetadataFields}）
 * @param extras      类型特有字段（导演、演员、画师…），落进 {@code metadata} jsonb
 * @param rawResponse 提供者的原始响应，原样存进 {@code raw_metadata}
 */
public record MetadataPatch(
        String source,
        String sourceId,
        Map<String, String> fields,
        Map<String, String> extras,
        String rawResponse) {

    public MetadataPatch {
        fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
        extras = extras == null ? Map.of() : new LinkedHashMap<>(extras);
    }

    public boolean isEmpty() {
        return fields.isEmpty() && extras.isEmpty();
    }
}
