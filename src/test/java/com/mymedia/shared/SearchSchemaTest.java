package com.mymedia.shared;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSchemaTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private List<String> indexNames(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }

    @Test
    void videoItemHasAGeneratedTsvectorColumn() {
        String generated = jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                 WHERE table_name = 'video_item' AND column_name = 'search_vector'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void imageNodeHasAGeneratedTsvectorColumn() {
        String generated = jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                 WHERE table_name = 'image_node' AND column_name = 'search_vector'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void bothSearchPathsAreIndexed() {
        assertThat(indexNames("video_item"))
                .contains("idx_video_item_title_trgm",        // 计划 03 建的
                          "idx_video_item_original_trgm",     // 本任务补的
                          "idx_video_item_fts");
        assertThat(indexNames("image_node"))
                .contains("idx_image_node_name_trgm",         // 计划 04 建的
                          "idx_image_node_title_trgm",        // 本任务补的
                          "idx_image_node_fts");
    }

    @Test
    void generatedColumnFollowsTheTitleWithoutAnyTrigger() {
        Long libraryId = jdbc.queryForObject("""
                INSERT INTO libraries (name, domain, root_path)
                VALUES ('搜索用库' || gen_random_uuid(), 'VIDEO', '/tmp/' || gen_random_uuid())
                RETURNING id
                """, Long.class);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, summary)
                VALUES (?, 'MOVIE', 'Big Buck Bunny', 'big buck bunny', 'A rabbit story')
                RETURNING id
                """, Long.class, libraryId);

        // 词干化：查 bunnies 能命中 Bunny
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM video_item
                 WHERE id = ? AND search_vector @@ plainto_tsquery('english','bunnies')
                """, Integer.class, itemId)).isEqualTo(1);

        jdbc.update("UPDATE video_item SET title = 'Sintel' WHERE id = ?", itemId);

        // 生成列自动跟随，不需要触发器
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM video_item
                 WHERE id = ? AND search_vector @@ plainto_tsquery('english','sintel')
                """, Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void chineseStaysOneTokenWhichIsWhyTrigramsAreTheMainPath() {
        // 这条断言是 ADR-006 的证据：tsvector 对中文无能为力
        assertThat(jdbc.queryForObject(
                "SELECT to_tsvector('english','进击的巨人')::text", String.class))
                .isEqualTo("'进击的巨人':1");
    }
}
