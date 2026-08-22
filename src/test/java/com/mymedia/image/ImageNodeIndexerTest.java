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

class ImageNodeIndexerTest extends AbstractIntegrationTest {

    @Autowired
    ImageNodeIndexer indexer;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary imageLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                "/media/" + UUID.randomUUID());
    }

    private Long scannedFile(Long libraryId, String relativePath, String extension) {
        return jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, 100, now(), ?, 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, libraryId, relativePath, extension);
    }

    @Test
    void buildsOneNodePerPathSegment() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "画师A/2024/合集X/001.jpg");

        assertThat(leaf.getName()).isEqualTo("合集X");
        assertThat(leaf.getDepth()).isEqualTo(3);
        assertThat(leaf.getSourceKind()).isEqualTo(ImageSourceKind.DIRECTORY);
    }

    @Test
    void materializedPathContainsAncestorIds() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "a/b/c/001.jpg");

        // '/1/17/93/' —— 结构由 id 组成，改名不会让它变化
        assertThat(leaf.getMaterializedPath()).endsWith("/" + leaf.getId() + "/");
        assertThat(leaf.getMaterializedPath().split("/")).hasSize(4);   // 首元素为空串
    }

    @Test
    void sortPathContainsAncestorSortKeys() {
        MediaLibrary library = imageLibrary();

        ImageNode leaf = indexer.directoryNodeFor(library.getId(), "第2卷/第10话/001.jpg");

        // 顺序路径由排序键组成，用于强制书模式下按目录顺序展开子树
        assertThat(leaf.getSortPath()).startsWith("/");
        assertThat(leaf.getSortPath()).endsWith(leaf.getSortKey() + "/");
        assertThat(leaf.getSortPath().split("/")).hasSize(3);
    }

    @Test
    void reusesExistingNodesOnSecondCall() {
        MediaLibrary library = imageLibrary();

        ImageNode first = indexer.directoryNodeFor(library.getId(), "画师A/2024/001.jpg");
        ImageNode second = indexer.directoryNodeFor(library.getId(), "画师A/2024/002.jpg");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void fileAtLibraryRootGetsANodeNamedAfterTheLibrary() {
        MediaLibrary library = imageLibrary();

        ImageNode node = indexer.directoryNodeFor(library.getId(), "散图.jpg");

        // 库根下的散图也必须能读，因此建一个以库名命名的顶层节点收容它们
        assertThat(node.getName()).isEqualTo(library.getName());
        assertThat(node.getParentId()).isNull();
        assertThat(node.getDepth()).isEqualTo(1);
    }

    @Test
    void archiveBecomesALeafNodeNamedWithoutExtension() {
        MediaLibrary library = imageLibrary();
        Long scannedId = scannedFile(library.getId(), "漫画/某作品/vol01.cbz", "cbz");

        ImageNode node = indexer.archiveNodeFor(library.getId(), "漫画/某作品/vol01.cbz", scannedId);

        assertThat(node.getName()).isEqualTo("vol01");
        assertThat(node.getSourceKind()).isEqualTo(ImageSourceKind.ARCHIVE);
        assertThat(node.getArchiveScannedFileId()).isEqualTo(scannedId);
        assertThat(node.getDepth()).isEqualTo(3);
    }

    @Test
    void archiveNodeIsReusedOnRescan() {
        MediaLibrary library = imageLibrary();
        Long scannedId = scannedFile(library.getId(), "漫画/vol01.cbz", "cbz");

        ImageNode first = indexer.archiveNodeFor(library.getId(), "漫画/vol01.cbz", scannedId);
        ImageNode second = indexer.archiveNodeFor(library.getId(), "漫画/vol01.cbz", scannedId);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void siblingsAreCreatedUnderTheSameParent() {
        MediaLibrary library = imageLibrary();

        ImageNode a = indexer.directoryNodeFor(library.getId(), "根/子A/001.jpg");
        ImageNode b = indexer.directoryNodeFor(library.getId(), "根/子B/001.jpg");

        assertThat(a.getParentId()).isEqualTo(b.getParentId());
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}