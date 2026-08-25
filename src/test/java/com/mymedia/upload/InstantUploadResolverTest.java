package com.mymedia.upload;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.SampledHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstantUploadResolverTest extends AbstractIntegrationTest {

    @Autowired
    InstantUploadResolver resolver;

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());
    }

    /** 造一个真实文件与对应的 scanned_file 行；hash 传 null 模拟「还没算过」。 */
    private Long place(String relativePath, byte[] content, String hash) throws IOException {
        Files.write(libraryRoot.resolve(relativePath), content);
        return jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime,
                                          content_hash, extension)
                VALUES (?, ?, ?, now(), ?, 'mp4') RETURNING id
                """, Long.class, library.getId(), relativePath, (long) content.length, hash);
    }

    private static byte[] bytes(String seed, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (seed.charAt(i % seed.length()) + i);
        }
        return data;
    }

    @Test
    void hitsDirectlyWhenTheHashIsAlreadyStored() throws Exception {
        byte[] content = bytes("alpha", 4096);
        String hash = SampledHash.of(Files.write(libraryRoot.resolve("tmp.bin"), content),
                content.length);
        Long id = place("a.mp4", content, hash);

        assertThat(resolver.resolve(library.getId(), content.length, hash))
                .get()
                .satisfies(file -> assertThat(file.getId()).isEqualTo(id));
    }

    @Test
    void hitsBySizeCandidateAndBackfillsTheHash() throws Exception {
        byte[] content = bytes("beta", 4096);
        Long id = place("b.mp4", content, null);
        String hash = SampledHash.of(libraryRoot.resolve("b.mp4"), content.length);

        assertThat(resolver.resolve(library.getId(), content.length, hash))
                .get()
                .satisfies(file -> assertThat(file.getId()).isEqualTo(id));

        // 顺带把哈希补齐了——这是这条兜底路径白捡的收益
        assertThat(jdbc.queryForObject(
                "SELECT content_hash FROM scanned_file WHERE id = ?", String.class, id))
                .isEqualTo(hash);
    }

    @Test
    void aSameSizedButDifferentFileIsNotAHit() throws Exception {
        byte[] mine = bytes("gamma", 4096);
        byte[] theirs = bytes("delta", 4096);
        place("c.mp4", theirs, null);
        String myHash = SampledHash.of(Files.write(libraryRoot.resolve("mine.bin"), mine),
                mine.length);

        assertThat(resolver.resolve(library.getId(), mine.length, myHash)).isEmpty();
    }

    @Test
    void looksAtAtMostEightSameSizedCandidates() throws Exception {
        byte[] content = bytes("epsilon", 2048);
        for (int i = 0; i < 12; i++) {
            place("pad" + i + ".mp4", bytes("pad" + i, 2048), null);
        }
        Long target = place("target.mp4", content, null);
        String hash = SampledHash.of(libraryRoot.resolve("target.mp4"), content.length);

        // target 排在第 13 位（按 id 升序），落在 8 个的窗口之外 → 不命中。
        // 这是有意的上界：不设限，一个库里几百个同尺寸文件会让创建会话读上几 GB
        assertThat(resolver.resolve(library.getId(), content.length, hash)).isEmpty();
        assertThat(target).isNotNull();
    }

    @Test
    void aFileFromAnotherLibraryIsNeverAHit() throws Exception {
        byte[] content = bytes("zeta", 4096);
        String hash = SampledHash.of(Files.write(libraryRoot.resolve("tmp2.bin"), content),
                content.length);
        place("d.mp4", content, hash);

        MediaLibrary other = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());

        assertThat(resolver.resolve(other.getId(), content.length, hash)).isEmpty();
    }

    @Test
    void aCandidateWhoseFileVanishedIsSkippedInsteadOfBlowingUp() throws Exception {
        byte[] content = bytes("eta", 4096);
        place("gone.mp4", content, null);
        Files.delete(libraryRoot.resolve("gone.mp4"));

        // 读不到就当没命中，正常走分片上传——秒传是优化，不是必需品
        assertThat(resolver.resolve(library.getId(), content.length, "0".repeat(64))).isEmpty();
    }
}
