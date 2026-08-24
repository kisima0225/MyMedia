package com.mymedia.video;

import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NaturalSortKey;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code video_item} 上四个非标量列的读写：
 * {@code metadata} / {@code raw_metadata} / {@code field_sources} / {@code locked_fields}。
 *
 * <p>全部走 {@link JdbcTemplate}，与计划 03、04 一致：jsonb 与 text[] 一律不做 JPA 映射
 * （{@code ddl-auto=validate} 对 Hibernate 的这两类映射很挑），而它们天然适合直读直写。
 *
 * <p>合并用 PostgreSQL 的 {@code ||}：jsonb 的 {@code ||} 是右侧覆盖左侧的浅合并，
 * 正好是"只更新这次写到的字段"；text[] 的 {@code ||} 是拼接，去重靠外面套一层
 * {@code SELECT DISTINCT unnest}。
 */
@Component
class VideoMetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    VideoMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> lockedFields(Long itemId) {
        return jdbc.queryForObject(
                "SELECT locked_fields FROM video_item WHERE id = ?",
                (rs, rowNum) -> toSet(rs.getArray(1)), itemId);
    }

    /**
     * 写入一批字段。
     *
     * <p>每个标量列都是 {@code COALESCE(?, 列)}：这次没写到的字段保持原值。
     * 日期与评分交给数据库 CAST，格式不对当场报错。
     */
    void applyFields(Long itemId, Map<String, String> fields, Map<String, String> extras,
                     String source, String sourceId, String rawResponse, ScrapeStatus status) {

        String title = fields.get(MetadataFields.TITLE);
        Map<String, String> sources = new LinkedHashMap<>();
        fields.keySet().forEach(field -> sources.put(field, source));
        extras.keySet().forEach(field -> sources.put(field, source));

        jdbc.update("""
                UPDATE video_item
                   SET title          = COALESCE(?, title),
                       sort_title     = COALESCE(?, sort_title),
                       original_title = COALESCE(?, original_title),
                       summary        = COALESCE(?, summary),
                       release_date   = COALESCE(CAST(? AS date), release_date),
                       rating         = COALESCE(CAST(? AS numeric), rating),
                       metadata       = metadata || CAST(? AS jsonb),
                       raw_metadata   = COALESCE(CAST(? AS jsonb), raw_metadata),
                       field_sources  = field_sources || CAST(? AS jsonb),
                       scrape_source  = COALESCE(?, scrape_source),
                       scrape_source_id = COALESCE(?, scrape_source_id),
                       scrape_status  = ?
                 WHERE id = ?
                """,
                title,
                title == null ? null : NaturalSortKey.of(title),
                fields.get(MetadataFields.ORIGINAL_TITLE),
                fields.get(MetadataFields.SUMMARY),
                fields.get(MetadataFields.RELEASE_DATE),
                fields.get(MetadataFields.RATING),
                toJson(extras),
                rawResponse,
                toJson(sources),
                source, sourceId, status.name(), itemId);
    }

    /** 把字段名加进锁定集合，去重。 */
    void lock(Long itemId, Set<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE video_item
                       SET locked_fields = ARRAY(SELECT DISTINCT unnest(locked_fields || ?))
                     WHERE id = ?
                    """);
            statement.setArray(1, connection.createArrayOf("text", fields.toArray(String[]::new)));
            statement.setLong(2, itemId);
            return statement;
        });
    }

    void updateStatus(Long itemId, ScrapeStatus status) {
        jdbc.update("UPDATE video_item SET scrape_status = ? WHERE id = ?", status.name(), itemId);
    }

    MetadataSnapshot snapshot(Long itemId) {
        return jdbc.queryForObject("""
                SELECT title, original_title, summary, release_date, rating,
                       field_sources::text AS sources, locked_fields,
                       scrape_status, scrape_source, scrape_source_id
                  FROM video_item WHERE id = ?
                """, (rs, rowNum) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, MetadataFields.TITLE, rs.getString("title"));
            putIfPresent(fields, MetadataFields.ORIGINAL_TITLE, rs.getString("original_title"));
            putIfPresent(fields, MetadataFields.SUMMARY, rs.getString("summary"));
            putIfPresent(fields, MetadataFields.RELEASE_DATE, rs.getString("release_date"));
            putIfPresent(fields, MetadataFields.RATING, rs.getString("rating"));
            return new MetadataSnapshot(
                    fields,
                    fromJson(rs.getString("sources")),
                    toSet(rs.getArray("locked_fields")),
                    ScrapeStatus.valueOf(rs.getString("scrape_status")),
                    rs.getString("scrape_source"),
                    rs.getString("scrape_source_id"));
        }, itemId);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static Set<String> toSet(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of((String[]) array.getArray()));
    }

    private static String toJson(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化元数据字段", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fromJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 field_sources", e);
        }
    }
}
