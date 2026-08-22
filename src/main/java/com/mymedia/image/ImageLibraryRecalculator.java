package com.mymedia.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次扫描结束后，整体重算一个图片库的页序与计数。
 *
 * <p>四步全部是<b>集合操作</b>，不在 Java 里循环行——几万张图的库也只是几条 SQL。
 */
@Service
class ImageLibraryRecalculator {

    private static final Logger log = LoggerFactory.getLogger(ImageLibraryRecalculator.class);

    /** 与 {@code mymedia.scan.max-depth} 同量级，用来给回收循环封顶。 */
    private static final int MAX_PRUNE_ROUNDS = 32;

    private final JdbcTemplate jdbc;

    ImageLibraryRecalculator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    void recalculate(Long libraryId) {
        int pruned = pruneEmptyDirectories(libraryId);
        int renumbered = renumberPages(libraryId);
        recountDirect(libraryId);
        recountSubtree(libraryId);
        log.debug("图片库重算完成 id={} 重编页={} 回收空节点={}", libraryId, renumbered, pruned);
    }

    /**
     * 页码重编号。
     *
     * <p>用窗口函数一条 SQL 完成：{@code row_number()} 按 {@code sort_key} 给同一节点下的
     * 页排序，序号直接写回 {@code page_index}。
     *
     * <p>为什么不在发现每个文件时分配页码：文件是一个个来的，中间插入一页会让
     * 后面所有页码全错，逐行修正是 O(n²) 次 UPDATE。
     *
     * <p>末尾的 {@code page_index <> rn - 1} 是为了跳过没变化的行——绝大多数扫描
     * 什么都没动，不应该产生任何写入。
     */
    private int renumberPages(Long libraryId) {
        return jdbc.update("""
                UPDATE image_file f
                SET page_index = t.rn - 1
                FROM (
                    SELECT f2.id,
                           row_number() OVER (PARTITION BY f2.node_id
                                              ORDER BY f2.sort_key, f2.id) AS rn
                    FROM image_file f2
                    JOIN image_node n ON n.id = f2.node_id
                    WHERE n.library_id = ?
                ) t
                WHERE f.id = t.id
                  AND f.page_index <> t.rn - 1
                """, libraryId);
    }

    /** 直属页数与直属子节点数。两条相关子查询，各自走已有索引。 */
    private void recountDirect(Long libraryId) {
        jdbc.update("""
                UPDATE image_node n
                SET direct_page_count = (SELECT count(*) FROM image_file f WHERE f.node_id = n.id),
                    child_node_count  = (SELECT count(*) FROM image_node c WHERE c.parent_id = n.id)
                WHERE n.library_id = ?
                """, libraryId);
    }

    /**
     * 子树页数聚合。
     *
     * <p>物化路径以斜杠收尾，因此 {@code LIKE '/1/17/%'} <b>不会</b>误匹配
     * {@code /1/170/}——这是物化路径最经典的 bug，收尾的那个斜杠就是防线。
     * 前缀本身也匹配自己（{@code '%'} 可以匹配空串），所以节点自己的直属页也被算进去。
     */
    private void recountSubtree(Long libraryId) {
        jdbc.update("""
                UPDATE image_node n
                SET total_page_count = COALESCE((
                        SELECT sum(d.direct_page_count)
                        FROM image_node d
                        WHERE d.library_id = n.library_id
                          AND d.materialized_path LIKE n.materialized_path || '%'), 0)
                WHERE n.library_id = ?
                """, libraryId);
    }

    /**
     * 回收既没有页也没有子节点的目录节点。
     *
     * <p>目录节点只在有文件需要它时才被创建，因此"零页零子节点"意味着内容已经
     * 全部移走或消失，节点是残留的空壳（改名与移动过程中会瞬时产生）。
     *
     * <p><b>只回收 DIRECTORY。</b>索引任务还没跑的 ARCHIVE 节点页数也是 0，
     * 把它当空节点删掉等于把整本书弄丢。
     *
     * <p>删掉一层可能让它的父节点也变空，所以要循环，用深度上限封顶。
     */
    private int pruneEmptyDirectories(Long libraryId) {
        int total = 0;
        for (int round = 0; round < MAX_PRUNE_ROUNDS; round++) {
            int deleted = jdbc.update("""
                    DELETE FROM image_node n
                    WHERE n.library_id = ?
                      AND n.source_kind = 'DIRECTORY'
                      AND NOT EXISTS (SELECT 1 FROM image_file f WHERE f.node_id = n.id)
                      AND NOT EXISTS (SELECT 1 FROM image_node c WHERE c.parent_id = n.id)
                    """, libraryId);
            total += deleted;
            if (deleted == 0) {
                return total;
            }
        }
        log.warn("空节点回收达到轮次上限 libraryId={}，树可能异常深", libraryId);
        return total;
    }
}