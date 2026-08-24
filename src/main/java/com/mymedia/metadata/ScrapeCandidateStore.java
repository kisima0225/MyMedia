package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code scrape_candidate} 的读写。走 {@link JdbcTemplate}：{@code payload} 是 jsonb，
 * 按项目约定不做 JPA 映射。
 */
@Component
class ScrapeCandidateStore {

    private static final RowMapper<ScrapeCandidateRecord> MAPPER = (rs, rowNum) -> {
        Long videoItemId = (Long) rs.getObject("video_item_id");
        return new ScrapeCandidateRecord(
                rs.getLong("id"),
                videoItemId != null ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                videoItemId != null ? videoItemId : rs.getLong("image_node_id"),
                rs.getString("provider"),
                rs.getString("external_id"),
                rs.getString("title"),
                (Integer) rs.getObject("year"),
                rs.getDouble("score"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcTemplate jdbc;

    ScrapeCandidateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 覆盖式写入：一次刮削的候选就是这个条目当前的全部候选。 */
    @Transactional
    void replaceAll(LibraryDomain domain, Long targetId, List<MetadataCandidate> candidates) {
        deleteAll(domain, targetId);
        String column = columnOf(domain);
        for (MetadataCandidate candidate : candidates) {
            jdbc.update("INSERT INTO scrape_candidate (" + column + ", provider, external_id,"
                            + " title, year, score, payload) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                    targetId, candidate.provider(), candidate.externalId(),
                    candidate.title(), candidate.year(), candidate.score(),
                    candidate.payload() == null ? "{}" : candidate.payload());
        }
    }

    @Transactional
    void deleteAll(LibraryDomain domain, Long targetId) {
        jdbc.update("DELETE FROM scrape_candidate WHERE " + columnOf(domain) + " = ?", targetId);
    }

    @Transactional(readOnly = true)
    List<ScrapeCandidateRecord> findByTarget(LibraryDomain domain, Long targetId) {
        return jdbc.query("SELECT * FROM scrape_candidate WHERE " + columnOf(domain) + " = ?"
                + " ORDER BY score DESC, id", MAPPER, targetId);
    }

    @Transactional(readOnly = true)
    ScrapeCandidateRecord getById(Long id) {
        return jdbc.query("SELECT * FROM scrape_candidate WHERE id = ?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("找不到刮削候选 id=" + id));
    }

    /** 列名由枚举决定，不是外部输入，拼进 SQL 是安全的。 */
    private static String columnOf(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_id" : "image_node_id";
    }
}
