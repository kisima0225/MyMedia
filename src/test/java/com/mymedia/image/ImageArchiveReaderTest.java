package com.mymedia.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageArchiveReaderTest {

    @TempDir
    Path dir;

    private final ImageArchiveReader reader = new ImageArchiveReader(StandardCharsets.UTF_8);

    private Path archive(String name, String... entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    zip.write(("data-" + entry).getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void listsImageEntriesOnly() throws IOException {
        Path cbz = archive("a.cbz", "001.jpg", "002.png", "readme.txt", "ComicInfo.xml");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("001.jpg", "002.png");
    }

    @Test
    void sortsPagesNaturallyNotLexically() throws IOException {
        Path cbz = archive("b.cbz", "10.jpg", "2.jpg", "1.jpg", "20.jpg");

        // 字典序会排成 1, 10, 2, 20 —— 读者会从第 1 页直接跳到第 10 页
        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("1.jpg", "2.jpg", "10.jpg", "20.jpg");
    }

    @Test
    void keepsNestedEntriesInDirectoryOrder() throws IOException {
        Path cbz = archive("c.cbz", "第2章/001.jpg", "第1章/002.jpg", "第1章/001.jpg");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("第1章/001.jpg", "第1章/002.jpg", "第2章/001.jpg");
    }

    @Test
    void skipsDirectoriesAndJunkEntries() throws IOException {
        Path cbz = archive("d.cbz",
                "images/", "images/001.jpg", "__MACOSX/._001.jpg", ".DS_Store", "Thumbs.db");

        assertThat(reader.listPages(cbz))
                .extracting(ArchivePage::entryName)
                .containsExactly("images/001.jpg");
    }

    @Test
    void readsASingleEntryWithoutExtractingTheWholeArchive() throws IOException {
        Path cbz = archive("e.cbz", "001.jpg", "002.jpg");

        try (InputStream in = reader.openEntry(cbz, "002.jpg")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("data-002.jpg");
        }

        // 目录里不应留下任何解压产物
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("e.cbz");
        }
    }

    @Test
    void closingTheStreamReleasesTheArchiveSoItCanBeDeleted() throws IOException {
        Path cbz = archive("f.cbz", "001.jpg");

        try (InputStream in = reader.openEntry(cbz, "001.jpg")) {
            in.readAllBytes();
        }

        // Windows 上被占用的文件删不掉 —— 这条断言就是文件句柄泄漏的探测器
        Files.delete(cbz);
        assertThat(Files.exists(cbz)).isFalse();
    }

    @Test
    void missingEntryFailsLoudly() throws IOException {
        Path cbz = archive("g.cbz", "001.jpg");

        assertThatThrownBy(() -> reader.openEntry(cbz, "999.jpg"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("999.jpg");
    }

    @Test
    void handlesArchiveWithNoImagesAtAll() throws IOException {
        Path cbz = archive("h.cbz", "readme.txt");

        assertThat(reader.listPages(cbz)).isEmpty();
    }
}