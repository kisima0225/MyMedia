package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code video_item_tag} / {@code image_node_tag} 的读写。
 *
 * <p>两张表是本模块自己的（和 {@code scrape_candidate} 一样，只是外键指向领域表），
 * 所以直接查是本模块的事，不算跨模块 SQL。但<b>条目的标题与封面在领域表里</b>，
 * 那个要绕 {@code VideoCatalogService.findByIds} 走。
 */
@Component
class TagLinkStore {

    private final JdbcTemplate jdbc;

    TagLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Long> tagIdsOf(LibraryDomain domain, Long targetId) {
        return jdbc.queryForList("SELECT tag_id FROM " + table(domain)
                + " WHERE " + targetColumn(domain) + " = ? ORDER BY tag_id", Long.class, targetId);
    }

    /** 整体替换：先清空再写入。调用方保证 tagIds 已去重且都属于 domain。 */
    void replace(LibraryDomain domain, Long targetId, List<Long> tagIds) {
        jdbc.update("DELETE FROM " + table(domain) + " WHERE " + targetColumn(domain) + " = ?",
                targetId);
        for (Long tagId : tagIds) {
            jdbc.update("INSERT INTO " + table(domain)
                    + " (" + targetColumn(domain) + ", tag_id) VALUES (?, ?)", targetId, tagId);
        }
    }

    List<Long> targetIdsWithTag(LibraryDomain domain, Long tagId, int limit) {
        return jdbc.queryForList("SELECT " + targetColumn(domain) + " FROM " + table(domain)
                        + " WHERE tag_id = ? ORDER BY " + targetColumn(domain) + " LIMIT ?",
                Long.class, tagId, limit);
    }

    /** 表名与列名由枚举决定，不是外部输入，拼进 SQL 是安全的。 */
    private static String table(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_tag" : "image_node_tag";
    }

    private static String targetColumn(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO ? "video_item_id" : "image_node_id";
    }
}
