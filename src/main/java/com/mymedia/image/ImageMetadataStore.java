package com.mymedia.image;

import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code image_node} 上非标量列的读写。
 *
 * <p>与视频域的差别只有一处：{@code image_node} <b>没有 release_date 与 rating 列</b>，
 * 这两个标准字段落进 {@code metadata} jsonb。漫画与图集的"评分""发行日期"
 * 远不像影视那样是一等公民，为它们加两列换不来查询上的好处。
 */
@Component
class ImageMetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 图片域没有对应列、要落进 jsonb 的标准字段。 */
    private static final Set<String> JSONB_ONLY_FIELDS =
            Set.of(MetadataFields.RELEASE_DATE, MetadataFields.RATING,
                    MetadataFields.ORIGINAL_TITLE);

    private final JdbcTemplate jdbc;

    ImageMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> lockedFields(Long nodeId) {
        return jdbc.queryForObject(
                "SELECT locked_fields FROM image_node WHERE id = ?",
                (rs, rowNum) -> toSet(rs.getArray(1)), nodeId);
    }

    void applyFields(Long nodeId, Map<String, String> fields, Map<String, String> extras,
                     String source, String sourceId, String rawResponse, ScrapeStatus status) {

        Map<String, String> jsonbPayload = new LinkedHashMap<>(extras);
        fields.forEach((field, value) -> {
            if (JSONB_ONLY_FIELDS.contains(field)) {
                jsonbPayload.put(field, value);
            }
        });

        Map<String, String> sources = new LinkedHashMap<>();
        fields.keySet().forEach(field -> sources.put(field, source));
        extras.keySet().forEach(field -> sources.put(field, source));

        jdbc.update("""
                UPDATE image_node
                   SET title         = COALESCE(?, title),
                       summary       = COALESCE(?, summary),
                       metadata      = metadata || CAST(? AS jsonb),
                       raw_metadata  = COALESCE(CAST(? AS jsonb), raw_metadata),
                       field_sources = field_sources || CAST(? AS jsonb),
                       scrape_source = COALESCE(?, scrape_source),
                       scrape_source_id = COALESCE(?, scrape_source_id),
                       scrape_status = ?
                 WHERE id = ?
                """,
                fields.get(MetadataFields.TITLE),
                fields.get(MetadataFields.SUMMARY),
                toJson(jsonbPayload),
                rawResponse,
                toJson(sources),
                source, sourceId, status.name(), nodeId);
    }

    void lock(Long nodeId, Set<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE image_node
                       SET locked_fields = ARRAY(SELECT DISTINCT unnest(locked_fields || ?))
                     WHERE id = ?
                    """);
            statement.setArray(1, connection.createArrayOf("text", fields.toArray(String[]::new)));
            statement.setLong(2, nodeId);
            return statement;
        });
    }

    void updateStatus(Long nodeId, ScrapeStatus status) {
        jdbc.update("UPDATE image_node SET scrape_status = ? WHERE id = ?", status.name(), nodeId);
    }

    MetadataSnapshot snapshot(Long nodeId) {
        return jdbc.queryForObject("""
                SELECT title, summary, metadata::text AS metadata, field_sources::text AS sources,
                       locked_fields, scrape_status, scrape_source, scrape_source_id
                  FROM image_node WHERE id = ?
                """, (rs, rowNum) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, MetadataFields.TITLE, rs.getString("title"));
            putIfPresent(fields, MetadataFields.SUMMARY, rs.getString("summary"));
            // 落在 jsonb 里的标准字段也要还原到统一视图上，界面不该关心它存在哪儿
            fromJson(rs.getString("metadata")).forEach((key, value) -> {
                if (MetadataFields.STANDARD.contains(key)) {
                    fields.put(key, value);
                }
            });
            return new MetadataSnapshot(
                    fields,
                    fromJson(rs.getString("sources")),
                    toSet(rs.getArray("locked_fields")),
                    ScrapeStatus.valueOf(rs.getString("scrape_status")),
                    rs.getString("scrape_source"),
                    rs.getString("scrape_source_id"));
        }, nodeId);
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
            throw new IllegalStateException("无法解析元数据 JSON", e);
        }
    }
}
