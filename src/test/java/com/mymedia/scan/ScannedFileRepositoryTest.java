package com.mymedia.scan;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScannedFileRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    ScannedFileQueryService queryService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/media/" + UUID.randomUUID());
    }

    private Long insertFile(Long libraryId, String path, long size) {
        return jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, ?, now(), 'mkv', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, libraryId, path, size);
    }

    @Test
    void findsFileByLibraryAndPath() {
        MediaLibrary library = newLibrary();
        insertFile(library.getId(), "电影/黑客帝国.mkv", 1024L);

        var found = queryService.findByPath(library.getId(), "电影/黑客帝国.mkv");

        assertThat(found).isPresent();
        assertThat(found.get().getSizeBytes()).isEqualTo(1024L);
        assertThat(found.get().getStatus()).isEqualTo(ScannedFileStatus.ACTIVE);
    }

    @Test
    void samePathInDifferentLibrariesIsAllowed() {
        MediaLibrary a = newLibrary();
        MediaLibrary b = newLibrary();

        insertFile(a.getId(), "同名.mkv", 1L);
        insertFile(b.getId(), "同名.mkv", 2L);

        assertThat(queryService.findByPath(a.getId(), "同名.mkv")).isPresent();
        assertThat(queryService.findByPath(b.getId(), "同名.mkv")).isPresent();
    }

    @Test
    void duplicatePathInSameLibraryIsRejected() {
        MediaLibrary library = newLibrary();
        insertFile(library.getId(), "重复.mkv", 1L);

        assertThatThrownBy(() -> insertFile(library.getId(), "重复.mkv", 2L))
                .hasMessageContaining("uq_scanned_file_path");
    }

    @Test
    void countActiveIgnoresMissingFiles() {
        MediaLibrary library = newLibrary();
        insertFile(library.getId(), "在.mkv", 1L);
        Long goneId = insertFile(library.getId(), "不在.mkv", 2L);
        jdbc.update("UPDATE scanned_file SET status = 'MISSING' WHERE id = ?", goneId);

        assertThat(queryService.countActive(library.getId())).isEqualTo(1L);
    }

    @Test
    void deletingLibraryCascadesToScannedFiles() {
        MediaLibrary library = newLibrary();
        insertFile(library.getId(), "级联.mkv", 1L);

        jdbc.update("DELETE FROM libraries WHERE id = ?", library.getId());

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM scanned_file WHERE library_id = ?",
                Integer.class, library.getId());
        assertThat(remaining).isZero();
    }
}
