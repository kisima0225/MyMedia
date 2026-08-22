package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
        "mymedia.jobs.poll-interval=PT1H"
})
class ImageContentBuilderTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

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

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE, root.toString());
    }

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + relative);
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

    /**
     * 扫描任务本身会再排出 ARCHIVE_INDEX 任务，后者不可能落进同一轮抢占的批次里，
     * 所以要多跑几轮。而且 {@code pollOnce()} 是<b>异步提交、同步返回</b>的——
     * 它返回不代表任务已经跑完。靠数 pollOnce() 的次数凑时序必然 flaky，
     * 这里改成「反复轮询直到队列排空」。
     */
    private void scan(Long libraryId) {
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

    @Test
    void looseImagesBecomePagesOfTheirDirectory() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("画师A/001.jpg");
        writeImage("画师A/002.jpg");

        scan(library.getId());

        List<ImageNode> roots = catalogService.findRoots(library.getId());
        assertThat(roots).extracting(ImageNode::getName).containsExactly("画师A");
        assertThat(catalogService.pagesOf(roots.getFirst().getId())).hasSize(2);
    }

    @Test
    void nestedDirectoriesBecomeATree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("画师A/2024/合集X/001.jpg");

        scan(library.getId());

        ImageNode artist = catalogService.findRoots(library.getId()).getFirst();
        ImageNode year = browseService.childNodes(library.getId(), artist.getId()).getFirst();
        ImageNode album = browseService.childNodes(library.getId(), year.getId()).getFirst();

        assertThat(artist.getName()).isEqualTo("画师A");
        assertThat(year.getName()).isEqualTo("2024");
        assertThat(album.getName()).isEqualTo("合集X");
        assertThat(album.getDepth()).isEqualTo(3);
    }

    @Test
    void archiveBecomesALeafNodeAndSchedulesAnIndexJob() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");

        int before = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'ARCHIVE_INDEX'", Integer.class);
        Long scanJobId = scanTrigger.requestScan(library.getId());
        // 只轮询一次：这一轮抢到的只可能是那个 LIBRARY_SCAN 任务（当时队列里没有别的）。
        // 然后<b>不再轮询</b>地等它结束，ARCHIVE_INDEX 就会停在 PENDING 上等着被数。
        // 若这里用「轮询到排空」的助手，索引任务会被顺手跑掉，这条用例就白写了。
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT status FROM job WHERE id = ?", String.class, scanJobId))
                        .isEqualTo("SUCCEEDED"));

        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        ImageNode volume = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        assertThat(volume.getName()).isEqualTo("vol01");
        assertThat(volume.getSourceKind()).isEqualTo(ImageSourceKind.ARCHIVE);

        Integer queued = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'ARCHIVE_INDEX'", Integer.class);
        assertThat(queued).isEqualTo(before + 1);

        // 断言完成后排空队列。上面只轮询一次把 ARCHIVE_INDEX 留在 PENDING 上，
        // 但那只是为了证明任务确实被排出；若不带走它，本用例的 @TempDir 一删，
        // 下一个测试抢到这条孤儿任务就会处理失败并进入退避重试，
        // pendingOrRunningJobs 永不归零，后续用例集体超时。
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobPoller.pollOnce();
            assertThat(pendingOrRunningJobs()).isZero();
        });
    }

    @Test
    void looseImageAtLibraryRootIsStillReadable() throws IOException {
        MediaLibrary library = libraryAtRoot();
        Files.writeString(root.resolve("散图.jpg"), "img");

        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(node.getName()).isEqualTo(library.getName());
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }

    @Test
    void rescanIsIdempotent() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");

        scan(library.getId());
        scan(library.getId());
        scan(library.getId());

        assertThat(catalogService.findRoots(library.getId())).hasSize(1);
        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }

    @Test
    void renamingAFileKeepsTheSameImageFileRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/旧名.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Long pageId = catalogService.pagesOf(node.getId()).getFirst().getId();

        Files.move(root.resolve("图集/旧名.jpg"), root.resolve("图集/新名.jpg"));
        scan(library.getId());

        // 改名走物理层，语义层通过外键跟随 —— image_file 行不变
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getId)
                .containsExactly(pageId);
    }

    @Test
    void vanishedFileKeepsItsPageRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Files.delete(root.resolve("图集/001.jpg"));
        scan(library.getId());

        // 扫描绝不删除数据：外接盘没挂载也会让文件「消失」，
        // 删掉意味着用户的阅读进度、收藏、手工元数据一并蒸发。
        assertThat(catalogService.pagesOf(node.getId())).hasSize(1);
    }

    @Test
    void changedArchiveIsReIndexed() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scan(library.getId());

        // 重打同一个包，多一页。size 变了 → 物理层判定为 changed。
        // 任务表跨用例共享，计数必须取相对增量，不能用绝对值。
        // 注意 before 在首轮扫描之后取值，首轮的登记任务已经算在里面，
        // 这里只断言「变化之后多出一个重排任务」。
        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'ARCHIVE_INDEX'", Integer.class);
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg", "003.jpg");
        scan(library.getId());

        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        ImageNode volume = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        assertThat(catalogService.pagesOf(volume.getId()))
                .extracting(ImageFile::getArchiveEntryName)
                .containsExactly("001.jpg", "002.jpg", "003.jpg");

        // 两轮扫描各排一个 ARCHIVE_INDEX：dedup_key 只压 PENDING/RUNNING，
        // 上一轮已经 SUCCEEDED，所以第二个任务能真正排出来。
        Integer jobs = jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE type = 'ARCHIVE_INDEX'", Integer.class);
        assertThat(jobs).isEqualTo(before + 1);
    }

    @Test
    void reactivatedArchiveIsReIndexedWithoutDuplicatingPages() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scan(library.getId());

        ImageNode comics = catalogService.findRoots(library.getId()).getFirst();
        Long volumeId = browseService.childNodes(library.getId(), comics.getId()).getFirst().getId();

        Files.delete(root.resolve("漫画/vol01.cbz"));
        scan(library.getId());                                   // → MISSING
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");     // 外接盘挂回来了
        scan(library.getId());                                   // → reactivated

        // 恢复走的是 changed(reactivated=true)，不是 discovered；
        // ARCHIVE_INDEX 处理器先删旧行再重建，页不会翻倍。
        assertThat(catalogService.pagesOf(volumeId)).hasSize(2);
    }

    @Test
    void changedLooseImageKeepsTheSamePageRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Long pageId = catalogService.pagesOf(node.getId()).getFirst().getId();

        Files.writeString(root.resolve("图集/001.jpg"), "改过的内容，长度不同");
        scan(library.getId());

        // 散图的内容变化对语义层没有影响：image_file 挂在 scanned_file_id 上，
        // 换了内容还是同一行，也不该重复登记。
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getId)
                .containsExactly(pageId);
    }
}