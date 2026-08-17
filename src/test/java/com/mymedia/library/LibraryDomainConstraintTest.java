package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'uq_library_domain' AND contype = 'u'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
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
        libraryService.create("图集", LibraryDomain.IMAGE, "/media/gallery");

        assertThatThrownBy(() ->
                libraryService.create("图集副本", LibraryDomain.IMAGE, "/media/gallery"))
                .isInstanceOf(Exception.class);
    }
}
