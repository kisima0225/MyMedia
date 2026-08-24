package com.mymedia.preview;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedAssetStorageTest extends AbstractIntegrationTest {

    @Autowired
    DerivedAssetService assetService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 直接插一行 scanned_file 当来源。本任务还没有扫描链路可用，
     * 而 derived_asset 只需要一个合法的外键目标。
     */
    private Long insertScannedFile() {
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, "/tmp/" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          extension, status, first_seen_at, last_seen_at)
                VALUES (?, ?, 1024, now(), 'mp4', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId(), "a/b/" + UUID.randomUUID() + ".mp4");
    }

    @Test
    void shardsPathIntoTwoLevelsBySourceFileId() throws IOException {
        Long sourceId = insertScannedFile();

        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);

        // covers/{id % 100}/{id}-cover.jpg
        assertThat(output.getFileName().toString()).isEqualTo(sourceId + "-cover.jpg");
        assertThat(output.getParent().getFileName().toString()).isEqualTo(String.valueOf(sourceId % 100));
        assertThat(output.getParent().getParent().getFileName().toString()).isEqualTo("covers");
        // 父目录必须已经建好，生成器直接写就行
        assertThat(Files.isDirectory(output.getParent())).isTrue();
    }

    @Test
    void recordsAssetWithSizeReadFromDisk() throws IOException {
        Long sourceId = insertScannedFile();
        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);
        Files.writeString(output, "0123456789", StandardCharsets.UTF_8);

        DerivedAsset asset = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        assertThat(asset.getId()).isNotNull();
        assertThat(asset.getKind()).isEqualTo(DerivedAssetKind.COVER);
        assertThat(asset.getSizeBytes()).isEqualTo(10L);
        assertThat(asset.getWidth()).isEqualTo(640);
        assertThat(asset.getRelativePath()).isEqualTo("covers/" + (sourceId % 100) + "/" + sourceId + "-cover.jpg");
        assertThat(assetService.pathOf(asset)).isEqualTo(output);
    }

    @Test
    void reRecordingSameSourceAndKindUpdatesInPlace() throws IOException {
        Long sourceId = insertScannedFile();
        Path output = assetService.prepare(DerivedAssetKind.COVER, sourceId);
        Files.writeString(output, "old", StandardCharsets.UTF_8);
        DerivedAsset first = assetService.record(DerivedAssetKind.COVER, sourceId, 640, 360);

        Files.writeString(output, "a much longer replacement", StandardCharsets.UTF_8);
        DerivedAsset second = assetService.record(DerivedAssetKind.COVER, sourceId, 800, 450);

        // 重新生成不能产生第二行——否则 cover_asset_id 会指向一个已被覆盖的旧文件
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getWidth()).isEqualTo(800);
        assertThat(second.getSizeBytes()).isEqualTo(25L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?",
                Integer.class, sourceId)).isEqualTo(1);
    }

    @Test
    void differentKindsCoexistForSameSource() throws IOException {
        Long sourceId = insertScannedFile();
        Files.writeString(assetService.prepare(DerivedAssetKind.COVER, sourceId), "c");
        Files.writeString(assetService.prepare(DerivedAssetKind.SPRITE_VTT, sourceId), "v");

        assetService.record(DerivedAssetKind.COVER, sourceId, null, null);
        assetService.record(DerivedAssetKind.SPRITE_VTT, sourceId, null, null);

        Optional<DerivedAsset> vtt = assetService.find(DerivedAssetKind.SPRITE_VTT, sourceId);
        assertThat(vtt).isPresent();
        assertThat(vtt.get().getRelativePath()).endsWith("-sprite.vtt");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM derived_asset WHERE source_scanned_file_id = ?",
                Integer.class, sourceId)).isEqualTo(2);
    }
}
