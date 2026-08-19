package com.mymedia.video;

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

class VideoDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary library(LibraryDomain domain) {
        return libraryService.create("库" + UUID.randomUUID(), domain, "/media/" + UUID.randomUUID());
    }

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'VIDEO', 'MOVIE', 'FLAT', ?, ?)
                RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @Test
    void videoItemCanBeCreatedInVideoLibrary() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        Long id = insertItem(videoLib.getId(), "黑客帝国");

        assertThat(id).isNotNull();
    }

    @Test
    void videoItemCannotLiveInImageLibrary() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        // 这是域分区的核心保证：复合外键让视频条目无法落进图片库
        assertThatThrownBy(() -> insertItem(imageLib.getId(), "不该存在"))
                .hasMessageContaining("fk_video_item_library_domain");
    }

    @Test
    void domainColumnCannotBeSetToImage() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'IMAGE', 'MOVIE', 'FLAT', 'x', 'x')
                """, videoLib.getId()))
                .hasMessageContaining("ck_video_item_is_video");
    }

    @Test
    void rejectsUnknownItemType() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_item (library_id, domain, item_type, structure, title, sort_title)
                VALUES (?, 'VIDEO', 'PODCAST', 'FLAT', 'x', 'x')
                """, videoLib.getId()))
                .hasMessageContaining("ck_video_item_type");
    }

    @Test
    void videoFileRequiresUniqueScannedFile() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);
        Long itemId = insertItem(videoLib.getId(), "片子");
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, 'a.mkv', 100, now(), 'mkv', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, videoLib.getId());

        jdbc.update("""
                INSERT INTO video_file (scanned_file_id, item_id, role)
                VALUES (?, ?, 'PRIMARY')
                """, scannedId, itemId);

        // 一个物理文件只能对应一个语义条目
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_file (scanned_file_id, item_id, role)
                VALUES (?, ?, 'VERSION')
                """, scannedId, itemId))
                .hasMessageContaining("uq_video_file_scanned");
    }

    @Test
    void deletingScannedFileCascadesToVideoFile() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);
        Long itemId = insertItem(videoLib.getId(), "片子");
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, 'b.mkv', 100, now(), 'mkv', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, videoLib.getId());
        jdbc.update("INSERT INTO video_file (scanned_file_id, item_id, role) VALUES (?, ?, 'PRIMARY')",
                scannedId, itemId);

        jdbc.update("DELETE FROM scanned_file WHERE id = ?", scannedId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM video_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(remaining).isZero();
    }
}
