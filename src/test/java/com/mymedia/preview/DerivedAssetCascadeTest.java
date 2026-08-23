package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedAssetCascadeTest extends AbstractIntegrationTest {

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletingDerivedAssetsNullsOutCoverReferencesInsteadOfFailing() throws IOException {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());

        Long sourceId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          extension, status, first_seen_at, last_seen_at)
                VALUES (?, ?, 1024, now(), 'mp4', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId(), UUID.randomUUID() + ".mp4");

        Files.writeString(assetService.prepare(DerivedAssetKind.COVER, sourceId), "cover");
        DerivedAsset cover = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title, cover_asset_id)
                VALUES (?, 'MOVIE', '测试影片', '测试影片', ?)
                RETURNING id
                """, Long.class, library.getId(), cover.getId());

        // 清空派生资源：不需要先解引用，外键 ON DELETE SET NULL 会替我们做
        jdbc.update("DELETE FROM derived_asset");

        Long remaining = jdbc.queryForObject(
                "SELECT cover_asset_id FROM video_item WHERE id = ?", Long.class, itemId);
        assertThat(remaining).isNull();
        // 用户数据一行不少
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_item WHERE id = ?", Integer.class, itemId)).isEqualTo(1);
    }
}