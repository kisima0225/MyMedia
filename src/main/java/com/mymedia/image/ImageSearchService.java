package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SearchQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 图片域搜索。结构与视频域一致（三元组子串 + tsvector，分层排序，见
 * {@link com.mymedia.video.VideoSearchService}），两处刻意不同：
 *
 * <ol>
 *   <li><b>搜两个名字</b>：{@code name} 是目录/压缩包原名（一定有），
 *       {@code title} 是刮削回来的标题（可能没有）。发布组风格的目录名与人认得的标题
 *       常常毫不相干，两个都要能搜到。</li>
 *   <li><b>只搜 ACTIVE 节点</b>：文件消失时扫描只标 {@code MISSING} 不删（计划 02 的铁律），
 *       但搜出一个点开就 404 的节点是很差的体验。</li>
 * </ol>
 *
 * <p>{@code substring_hit} 用 {@code coalesce(..., false)} 包一层：{@code title} 为
 * {@code null} 且 {@code name} 不匹配时，{@code false OR NULL} 是 SQL null 而非
 * {@code false}，在 {@code DESC} 排序里 null 的位置不确定，会把子串未命中的行插进
 * 命中的那一层。分数计算放进 {@code WITH scored AS (...)} CTE、排序挪到外层查询，
 * 是因为拍平成一条 {@code ORDER BY substring_hit DESC, trgm_score DESC, fts_score DESC}
 * 会让子串命中层的行也参与比较 {@code fts_score}（反之亦然）——两层的分数根本不在
 * 同一把尺子上，用 {@code CASE WHEN substring_hit THEN trgm_score END} 把不属于本层
 * 的分数置为 null 排到后面，才能保证每层只用自己的分数排序。
 */
@Service
public class ImageSearchService {

    private static final String SQL = """
            WITH scored AS (
                SELECT n.id, n.library_id, n.name, n.title, n.cover_asset_id,
                       n.total_page_count, n.direct_page_count, n.sort_key,
                       coalesce((n.name ILIKE :pattern ESCAPE '\\'
                        OR n.title ILIKE :pattern ESCAPE '\\'), false) AS substring_hit,
                       greatest(similarity(lower(n.name), :lowered),
                                similarity(lower(coalesce(n.title, '')), :lowered))
                           AS trgm_score,
                       ts_rank(n.search_vector, plainto_tsquery('english', :raw)) AS fts_score
                  FROM image_node n
                 WHERE n.library_id IN (:libraryIds)
                   AND n.status = 'ACTIVE'
                   AND (n.name ILIKE :pattern ESCAPE '\\'
                        OR n.title ILIKE :pattern ESCAPE '\\'
                        OR n.search_vector @@ plainto_tsquery('english', :raw))
            )
            SELECT id, library_id, name, title, cover_asset_id,
                   total_page_count, direct_page_count,
                   substring_hit, trgm_score, fts_score
              FROM scored
             ORDER BY substring_hit DESC,
                      CASE WHEN substring_hit THEN trgm_score END DESC,
                      CASE WHEN NOT substring_hit THEN fts_score END DESC,
                      sort_key
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LibraryAccessService accessService;

    ImageSearchService(NamedParameterJdbcTemplate jdbc, LibraryAccessService accessService) {
        this.jdbc = jdbc;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<ImageSearchHit> search(Long userId, SearchQuery query, int limit) {
        List<Long> libraryIds = accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.IMAGE)
                .map(MediaLibrary::getId)
                .toList();
        if (libraryIds.isEmpty()) {
            // IN () 是语法错误，必须在进 SQL 之前短路
            return List.of();
        }

        Map<String, Object> parameters = new MapSqlParameterSource()
                .addValue("pattern", query.likePattern())
                .addValue("lowered", query.lowered())
                .addValue("raw", query.normalized())
                .addValue("libraryIds", libraryIds)
                .addValue("limit", limit)
                .getValues();

        return jdbc.query(SQL, parameters, (rs, rowNum) -> new ImageSearchHit(
                rs.getLong("id"),
                rs.getLong("library_id"),
                rs.getString("name"),
                rs.getString("title"),
                (Long) rs.getObject("cover_asset_id"),
                rs.getInt("total_page_count"),
                rs.getInt("direct_page_count") > 0,
                rs.getBoolean("substring_hit")
                        ? rs.getDouble("trgm_score")
                        : rs.getDouble("fts_score")));
    }
}
