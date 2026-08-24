package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 视频域搜索：三元组子串 + tsvector 全文，取并集、分层排序。
 *
 * <p><b>排序为什么是分层的</b>：{@code similarity()} 落在 0–1，{@code ts_rank()} 是 0.0X 量级，
 * 两个分数不在一个尺度上，加权求和只是把"我不知道怎么比"包装成一个数字。分层之后
 * 每一层内部的比较都有意义，层与层之间是优先级：
 * 子串命中 → 相似度 → ts_rank → sort_title 兜底。
 *
 * <p><b>中文两字查询会走全表扫描</b>（实测 10 万行 29ms，与顺序扫描持平）——
 * pg_trgm 从 {@code %..%} 模式里提不出完整三元组。这是已知且接受的上界，见 ADR-006。
 */
@Service
public class VideoSearchService {

    private static final Logger log = LoggerFactory.getLogger(VideoSearchService.class);

    private static final String SQL = """
            SELECT vi.id, vi.library_id, vi.title, vi.sort_title, vi.cover_asset_id,
                   coalesce((vi.title ILIKE :pattern ESCAPE '\\'
                    OR vi.original_title ILIKE :pattern ESCAPE '\\'), false) AS substring_hit,
                   greatest(similarity(lower(vi.title), :lowered),
                            similarity(lower(coalesce(vi.original_title, '')), :lowered))
                       AS trgm_score,
                   ts_rank(vi.search_vector, plainto_tsquery('english', :raw)) AS fts_score
              FROM video_item vi
             WHERE vi.library_id IN (:libraryIds)
               AND (vi.title ILIKE :pattern ESCAPE '\\'
                    OR vi.original_title ILIKE :pattern ESCAPE '\\'
                    OR vi.search_vector @@ plainto_tsquery('english', :raw))
             ORDER BY substring_hit DESC, trgm_score DESC, fts_score DESC, vi.sort_title
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LibraryAccessService accessService;

    VideoSearchService(NamedParameterJdbcTemplate jdbc, LibraryAccessService accessService) {
        this.jdbc = jdbc;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<VideoSearchHit> search(Long userId, SearchQuery query, int limit) {
        List<Long> libraryIds = accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.VIDEO)
                .map(MediaLibrary::getId)
                .toList();
        if (libraryIds.isEmpty()) {
            // IN () 是语法错误，必须在进 SQL 之前短路
            return List.of();
        }

        if (!query.usesTrigramIndex()) {
            log.debug("搜索词 '{}' 不足 3 个码点，三元组索引不起作用，本次为全表扫描",
                    query.normalized());
        }

        Map<String, Object> parameters = new MapSqlParameterSource()
                .addValue("pattern", query.likePattern())
                .addValue("lowered", query.lowered())
                .addValue("raw", query.normalized())
                .addValue("libraryIds", libraryIds)
                .addValue("limit", limit)
                .getValues();

        return jdbc.query(SQL, parameters, (rs, rowNum) -> new VideoSearchHit(
                rs.getLong("id"),
                rs.getLong("library_id"),
                rs.getString("title"),
                rs.getString("sort_title"),
                (Long) rs.getObject("cover_asset_id"),
                rs.getBoolean("substring_hit")
                        ? rs.getDouble("trgm_score")
                        : rs.getDouble("fts_score")));
    }
}
