package com.mymedia.image;

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

class ImageDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary library(LibraryDomain domain) {
        return libraryService.create("库" + UUID.randomUUID(), domain, "/media/" + UUID.randomUUID());
    }

    private Long insertDirectoryNode(Long libraryId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, ?, ?, 'DIRECTORY')
                RETURNING id
                """, Long.class, libraryId, name, name);
    }

    private Long insertScannedFile(Long libraryId, String relativePath, String extension) {
        return jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, 100, now(), ?, 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, libraryId, relativePath, extension);
    }

    @Test
    void imageNodeCanBeCreatedInImageLibrary() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        Long id = insertDirectoryNode(imageLib.getId(), "画师A");

        assertThat(id).isNotNull();
    }

    @Test
    void imageNodeCannotLiveInVideoLibrary() {
        MediaLibrary videoLib = library(LibraryDomain.VIDEO);

        // 域分区的核心保证：复合外键让图片节点无法落进视频库
        assertThatThrownBy(() -> insertDirectoryNode(videoLib.getId(), "不该存在"))
                .hasMessageContaining("fk_image_node_library_domain");
    }

    @Test
    void domainColumnCannotBeSetToVideo() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'VIDEO', '/', '/', 0, 'x', 'x', 'DIRECTORY')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_is_image");
    }

    @Test
    void rejectsUnknownSourceKind() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, 'x', 'x', 'RAR_VOLUME')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_source_kind");
    }

    @Test
    void archiveNodeMustReferenceItsArchiveFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);

        // ARCHIVE 节点没有压缩包本体就无从读页，数据库层面直接堵死
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, 'vol01', 'vol01', 'ARCHIVE')
                """, imageLib.getId()))
                .hasMessageContaining("ck_image_node_archive_ref");
    }

    @Test
    void directoryNodeMustNotReferenceAnArchiveFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long scannedId = insertScannedFile(imageLib.getId(), "vol01.cbz", "cbz");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key,
                     source_kind, archive_scanned_file_id)
                VALUES (?, 'IMAGE', '/', '/', 0, '目录', '目录', 'DIRECTORY', ?)
                """, imageLib.getId(), scannedId))
                .hasMessageContaining("ck_image_node_archive_ref");
    }

    @Test
    void siblingNamesAreUniqueEvenAtRootWhereParentIsNull() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        insertDirectoryNode(imageLib.getId(), "同名");

        // PostgreSQL 默认 NULL 互不相等，(library_id, NULL, name) 不会冲突。
        // 本表用 UNIQUE NULLS NOT DISTINCT（PG 15+）修掉这个经典漏洞。
        assertThatThrownBy(() -> insertDirectoryNode(imageLib.getId(), "同名"))
                .hasMessageContaining("uq_image_node_sibling");
    }

    @Test
    void oneArchiveHoldsManyPages() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "作品");
        Long scannedId = insertScannedFile(imageLib.getId(), "vol01.cbz", "cbz");

        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key, archive_entry_name)
                VALUES (?, ?, 0, '001', '001.jpg')
                """, scannedId, nodeId);
        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key, archive_entry_name)
                VALUES (?, ?, 1, '002', '002.jpg')
                """, scannedId, nodeId);

        Integer pages = jdbc.queryForObject(
                "SELECT count(*) FROM image_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(pages).isEqualTo(2);
    }

    @Test
    void looseImageCannotBeRegisteredTwice() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "图集");
        Long scannedId = insertScannedFile(imageLib.getId(), "图集/001.jpg", "jpg");

        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 0, '001')
                """, scannedId, nodeId);

        // archive_entry_name 为 NULL 的两行也必须判为重复，同样靠 NULLS NOT DISTINCT
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 1, '001')
                """, scannedId, nodeId))
                .hasMessageContaining("uq_image_file_entry");
    }

    @Test
    void deletingScannedFileCascadesToImageFile() {
        MediaLibrary imageLib = library(LibraryDomain.IMAGE);
        Long nodeId = insertDirectoryNode(imageLib.getId(), "图集2");
        Long scannedId = insertScannedFile(imageLib.getId(), "图集2/001.jpg", "jpg");
        jdbc.update("""
                INSERT INTO image_file (scanned_file_id, node_id, page_index, sort_key)
                VALUES (?, ?, 0, '001')
                """, scannedId, nodeId);

        jdbc.update("DELETE FROM scanned_file WHERE id = ?", scannedId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM image_file WHERE scanned_file_id = ?", Integer.class, scannedId);
        assertThat(remaining).isZero();
    }
}