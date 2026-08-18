package com.mymedia.scan;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RelocationDetectorTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    LibraryScanner scanner;

    @Autowired
    ScannedFileQueryService queryService;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                root.toString());
    }

    private void writeMedia(String relative, long seed) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        byte[] content = new byte[4096];
        new Random(seed).nextBytes(content);
        Files.write(file, content);
    }

    @Test
    void renamedFileKeepsItsIdentity() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("旧名字.mkv", 1L);
        scanner.scan(library.getId());

        Long originalId = queryService.findByPath(library.getId(), "旧名字.mkv")
                .orElseThrow().getId();

        Files.move(root.resolve("旧名字.mkv"), root.resolve("新名字.mkv"));
        ScanOutcome outcome = scanner.scan(library.getId());

        assertThat(outcome.relocated()).isEqualTo(1);

        // 核心断言：id 不变。语义层与用户观看进度通过外键引用 id，因此全部保留。
        var moved = queryService.findByPath(library.getId(), "新名字.mkv");
        assertThat(moved).isPresent();
        assertThat(moved.get().getId()).isEqualTo(originalId);
        assertThat(moved.get().getStatus()).isEqualTo(ScannedFileStatus.ACTIVE);

        assertThat(queryService.findByPath(library.getId(), "旧名字.mkv")).isEmpty();
    }

    @Test
    void movedToAnotherDirectoryKeepsItsIdentity() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("待整理/影片.mkv", 2L);
        scanner.scan(library.getId());
        Long originalId = queryService.findByPath(library.getId(), "待整理/影片.mkv")
                .orElseThrow().getId();

        Files.createDirectories(root.resolve("电影"));
        Files.move(root.resolve("待整理/影片.mkv"), root.resolve("电影/影片.mkv"));
        scanner.scan(library.getId());

        assertThat(queryService.findByPath(library.getId(), "电影/影片.mkv")
                .orElseThrow().getId()).isEqualTo(originalId);
    }

    @Test
    void genuinelyNewFileIsNotMistakenForRelocation() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("a.mkv", 1L);
        scanner.scan(library.getId());

        writeMedia("b.mkv", 999L);   // 内容不同的全新文件
        ScanOutcome outcome = scanner.scan(library.getId());

        assertThat(outcome.relocated()).isZero();
        assertThat(outcome.added()).isEqualTo(1);
        assertThat(queryService.countActive(library.getId())).isEqualTo(2L);
    }

    @Test
    void deletedFileStaysMissingWhenNothingMatches() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("会被删.mkv", 3L);
        scanner.scan(library.getId());

        Files.delete(root.resolve("会被删.mkv"));
        ScanOutcome outcome = scanner.scan(library.getId());

        assertThat(outcome.relocated()).isZero();
        assertThat(outcome.vanished()).isEqualTo(1);
        assertThat(queryService.findByPath(library.getId(), "会被删.mkv")
                .orElseThrow().getStatus()).isEqualTo(ScannedFileStatus.MISSING);
    }

    @Test
    void historicalMissingFileIsNotUsedForRelocation() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("旧位置.mkv", 7L);
        scanner.scan(library.getId());
        Long originalId = queryService.findByPath(library.getId(), "旧位置.mkv")
                .orElseThrow().getId();

        Files.delete(root.resolve("旧位置.mkv"));
        scanner.scan(library.getId());

        writeMedia("新文件.mkv", 7L); // 与历史 MISSING 文件内容相同，但不是本轮移动
        ScanOutcome outcome = scanner.scan(library.getId());

        assertThat(outcome.relocated()).isZero();
        assertThat(outcome.added()).isEqualTo(1);
        assertThat(queryService.findByPath(library.getId(), "新文件.mkv")
                .orElseThrow().getId()).isNotEqualTo(originalId);
        assertThat(queryService.findByPath(library.getId(), "旧位置.mkv")
                .orElseThrow().getStatus()).isEqualTo(ScannedFileStatus.MISSING);
    }

    @Test
    void copyThenDeleteOriginalIsDetectedAsRelocation() throws IOException {
        // 用户先复制到新位置、再删掉旧的 —— 从扫描视角与移动无法区分，
        // 判成移动是正确行为：内容相同，保留进度才是用户想要的。
        MediaLibrary library = libraryAtRoot();
        writeMedia("原位置.mkv", 5L);
        scanner.scan(library.getId());
        Long originalId = queryService.findByPath(library.getId(), "原位置.mkv")
                .orElseThrow().getId();

        Files.copy(root.resolve("原位置.mkv"), root.resolve("新位置.mkv"));
        Files.delete(root.resolve("原位置.mkv"));
        scanner.scan(library.getId());

        assertThat(queryService.findByPath(library.getId(), "新位置.mkv")
                .orElseThrow().getId()).isEqualTo(originalId);
    }
}
