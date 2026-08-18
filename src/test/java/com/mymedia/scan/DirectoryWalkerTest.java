package com.mymedia.scan;

import com.mymedia.scan.spi.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryWalkerTest {

    @TempDir
    Path root;

    private final DirectoryWalker walker = new DirectoryWalker(32);

    private void touch(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, "x");
    }

    @Test
    void findsMediaFilesRecursively() throws IOException {
        touch("电影/黑客帝国.mkv");
        touch("番剧/进击的巨人/S01/E01.mkv");
        touch("番剧/进击的巨人/S01/E02.mkv");

        List<ScannedEntry> entries = walker.walk(root);

        assertThat(entries).extracting(ScannedEntry::relativePath)
                .containsExactlyInAnyOrder(
                        "电影/黑客帝国.mkv",
                        "番剧/进击的巨人/S01/E01.mkv",
                        "番剧/进击的巨人/S01/E02.mkv");
    }

    @Test
    void usesForwardSlashesRegardlessOfPlatform() throws IOException {
        touch("a/b/c.mkv");

        List<ScannedEntry> entries = walker.walk(root);

        // 路径存库后要跨平台可比，统一用正斜杠
        assertThat(entries).singleElement()
                .extracting(ScannedEntry::relativePath)
                .isEqualTo("a/b/c.mkv");
    }

    @Test
    void skipsIgnoredFileTypes() throws IOException {
        touch("电影/黑客帝国.mkv");
        touch("电影/黑客帝国.nfo");
        touch("电影/黑客帝国.srt");
        touch("电影/desktop.ini");

        List<ScannedEntry> entries = walker.walk(root);

        assertThat(entries).extracting(ScannedEntry::relativePath)
                .containsExactly("电影/黑客帝国.mkv");
    }

    @Test
    void capturesSizeAndKind() throws IOException {
        Path file = root.resolve("a.mkv");
        Files.writeString(file, "0123456789");

        ScannedEntry entry = walker.walk(root).getFirst();

        assertThat(entry.sizeBytes()).isEqualTo(10L);
        assertThat(entry.extension()).isEqualTo("mkv");
        assertThat(entry.mtime()).isNotNull();
        assertThat(entry.kind()).isEqualTo(MediaKind.VIDEO);
    }

    @Test
    void respectsMaxDepth() throws IOException {
        touch("a/b/c/d/deep.mkv");
        touch("shallow.mkv");

        DirectoryWalker shallowWalker = new DirectoryWalker(2);
        List<ScannedEntry> entries = shallowWalker.walk(root);

        assertThat(entries).extracting(ScannedEntry::relativePath)
                .containsExactly("shallow.mkv");
    }

    @Test
    void doesNotLoopOnSymlinkCycle() throws IOException {
        touch("real/movie.mkv");
        Path link = root.resolve("real/loop");
        try {
            Files.createSymbolicLink(link, root.resolve("real"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 上创建符号链接需要管理员权限或开发者模式
            assumeTrue(false, "本环境无法创建符号链接，跳过");
        }

        // 若不做环检测，这里会无限递归直到栈溢出或超时
        List<ScannedEntry> entries = walker.walk(root);

        assertThat(entries).extracting(ScannedEntry::relativePath)
                .contains("real/movie.mkv");
        assertThat(entries).hasSizeLessThan(10);
    }

    @Test
    void emptyDirectoryYieldsNoEntries() throws IOException {
        Files.createDirectories(root.resolve("空目录"));

        assertThat(walker.walk(root)).isEmpty();
    }
}
