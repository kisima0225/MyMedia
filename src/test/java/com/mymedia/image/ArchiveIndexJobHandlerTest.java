package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class ArchiveIndexJobHandlerTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ImageCatalogService catalogService;

    @Autowired
    ImageBrowseService browseService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeArchive(String relative, String... entryNames) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String entry : entryNames) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("page-" + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** 轮询到队列排空为止：扫描会再排出 ARCHIVE_INDEX，而 pollOnce() 是异步提交、同步返回的。 */
    private void scanAndIndex(Long libraryId) {
        scanTrigger.requestScan(libraryId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(pendingOrRunningJobs()).isZero();
        });
    }

    private int pendingOrRunningJobs() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE status IN ('PENDING', 'RUNNING')", Integer.class);
    }

    private ImageNode archiveNodeOf(MediaLibrary library) {
        ImageNode top = catalogService.findRoots(library.getId()).getFirst();
        return browseService.childNodes(library.getId(), top.getId()).getFirst();
    }

    @Test
    void indexesEveryPageOfTheArchive() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg", "003.jpg");

        scanAndIndex(library.getId());

        List<ImageFile> pages = catalogService.pagesOf(archiveNodeOf(library).getId());
        assertThat(pages).extracting(ImageFile::getArchiveEntryName)
                .containsExactly("001.jpg", "002.jpg", "003.jpg");
    }

    @Test
    void pageIndexIsAssignedInNaturalOrder() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol02.cbz", "10.jpg", "2.jpg", "1.jpg");

        scanAndIndex(library.getId());

        assertThat(catalogService.pagesOf(archiveNodeOf(library).getId()))
                .extracting(ImageFile::getPageIndex, ImageFile::getArchiveEntryName)
                .containsExactly(
                        Tuple.tuple(0, "1.jpg"),
                        Tuple.tuple(1, "2.jpg"),
                        Tuple.tuple(2, "10.jpg"));
    }

    @Test
    void allPagesShareOneScannedFile() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol03.cbz", "001.jpg", "002.jpg");

        scanAndIndex(library.getId());

        List<ImageFile> pages = catalogService.pagesOf(archiveNodeOf(library).getId());
        // 一个压缩包是一个物理文件，N 个条目是 N 个语义页
        assertThat(pages).extracting(ImageFile::getScannedFileId)
                .containsOnly(pages.getFirst().getScannedFileId());
    }

    @Test
    void reindexingDoesNotDuplicatePages() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol04.cbz", "001.jpg", "002.jpg");

        scanAndIndex(library.getId());
        scanAndIndex(library.getId());

        assertThat(catalogService.pagesOf(archiveNodeOf(library).getId())).hasSize(2);
    }

    @Test
    void indexRefreshesNodeCountsSoTheBookIsImmediatelyReadable() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol05.cbz", "001.jpg", "002.jpg", "003.jpg");

        scanAndIndex(library.getId());

        // 索引任务在扫描收尾的重算之后才填页，必须自己刷新计数，
        // 否则新索引的书 directPageCount=0、readable=false，要等下次扫描才可读。
        ImageNode volume = archiveNodeOf(library);
        assertThat(volume.getDirectPageCount()).isEqualTo(3);
        assertThat(volume.getTotalPageCount()).isEqualTo(3);
        assertThat(volume.isReadable()).isTrue();
        assertThat(catalogService.findRoots(library.getId()).getFirst().getTotalPageCount())
                .isEqualTo(3);
    }
}
