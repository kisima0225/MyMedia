package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsVideoLibrary() {
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, "/media/movies");

        assertThat(library.getId()).isNotNull();
        assertThat(library.getDomain()).isEqualTo(LibraryDomain.VIDEO);
    }

    @Test
    void domainIsExposedAsCompositeUniqueKeyForForeignKeyReference() {
        // libraries 上必须有 (id, domain) 唯一键，否则子表无法用复合外键引用它。
        var key = jdbc.queryForMap("""
                SELECT c.conrelid::regclass::text AS table_name,
                       array_to_string(array_agg(a.attname ORDER BY key.ordinality), ',') AS columns
                FROM pg_constraint c
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS key(attnum, ordinality)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = key.attnum
                WHERE c.conname = 'uq_library_domain' AND c.contype = 'u'
                GROUP BY c.conrelid
                """);

        assertThat(key).containsEntry("table_name", "libraries")
                .containsEntry("columns", "id,domain");
    }

    @Test
    void rejectsUnknownDomainValue() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO libraries (name, domain, root_path, enabled, created_at)
                VALUES ('坏库', 'AUDIO', '/media/audio', true, now())
                """))
                .hasMessageContaining("ck_libraries_domain");
    }

    @Test
    void rejectsDuplicateRootPath() {
        String rootPath = "/media/gallery-" + UUID.randomUUID();
        libraryService.create("图集", LibraryDomain.IMAGE, rootPath);

        assertThatThrownBy(() ->
                libraryService.create("图集副本", LibraryDomain.IMAGE, rootPath))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("libraries_root_path_key");
    }
}
