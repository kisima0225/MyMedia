package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H"
})
class ImageLibraryRecalculatorTest extends AbstractIntegrationTest {

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
    ImageScanFinalizer finalizer;

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

    /** 轮询到队列排空为止，理由见 Global Constraints「需要跑任务的集成测试」。 */
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
    void pageIndexFollowsNaturalOrderNotDiscoveryOrder() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/10.jpg");
        writeImage("图集/2.jpg");
        writeImage("图集/1.jpg");

        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getPageIndex)
                .containsExactly(0, 1, 2);
        assertThat(catalogService.pagesOf(node.getId()))
                .extracting(ImageFile::getSortKey)
                .isSorted();
    }

    @Test
    void insertingAPageInTheMiddleShiftsTheLaterOnes() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("图集/001.jpg");
        writeImage("图集/003.jpg");
        scan(library.getId());

        ImageNode node = catalogService.findRoots(library.getId()).getFirst();
        Long thirdPageId = catalogService.pagesOf(node.getId()).getLast().getId();

        writeImage("图集/002.jpg");
        scan(library.getId());

        // 003 原本是第 1 页（0 基），插入 002 之后必须变成第 2 页。
        // 这就是页码不能在发现时逐个分配的原因。
        ImageFile third = catalogService.pagesOf(node.getId()).stream()
                .filter(page -> page.getId().equals(thirdPageId)).findFirst().orElseThrow();
        assertThat(third.getPageIndex()).isEqualTo(2);
    }

    @Test
    void countsAreMaintainedPerNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品/封面.jpg");
        writeImage("作品/第1话/001.jpg");
        writeImage("作品/第1话/002.jpg");
        writeImage("作品/第2话/001.jpg");

        scan(library.getId());

        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getDirectPageCount()).isEqualTo(1);
        assertThat(work.getChildNodeCount()).isEqualTo(2);
        // 既可读又可浏览 —— 「书」与「文件夹」不是互斥类型
        assertThat(work.isReadable()).isTrue();
        assertThat(work.isBrowsable()).isTrue();
    }

    @Test
    void totalPageCountAggregatesTheWholeSubtree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品/封面.jpg");
        writeImage("作品/第1话/001.jpg");
        writeImage("作品/第1话/002.jpg");
        writeImage("作品/第2话/001.jpg");

        scan(library.getId());

        ImageNode work = catalogService.findRoots(library.getId()).getFirst();
        assertThat(work.getTotalPageCount()).isEqualTo(4);

        ImageNode chapterOne = browseService.childNodes(library.getId(), work.getId()).getFirst();
        assertThat(chapterOne.getTotalPageCount()).isEqualTo(2);
    }

    @Test
    void siblingSubtreesDoNotLeakIntoEachOther() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作品A/001.jpg");
        writeImage("作品B/001.jpg");
        writeImage("作品B/002.jpg");

        scan(library.getId());

        var roots = catalogService.findRoots(library.getId());
        assertThat(roots).extracting(ImageNode::getName).containsExactly("作品A", "作品B");
        assertThat(roots.get(0).getTotalPageCount()).isEqualTo(1);
        assertThat(roots.get(1).getTotalPageCount()).isEqualTo(2);
    }

    @Test
    void emptyDirectoryNodesArePruned() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("有内容/001.jpg");
        scan(library.getId());

        // 手工制造一个空壳节点——扫描过程中改名/移动会瞬时产生这种节点
        jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key, source_kind)
                VALUES (?, 'IMAGE', '/', '/', 0, '空壳', '空壳', 'DIRECTORY')
                """, library.getId());

        finalizer.onScanCompleted(new LibraryScanCompleted(library.getId(), 0, 0, 0, 0));

        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("有内容");
    }

    @Test
    void emptyArchiveNodeIsNeverPruned() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("占位/001.jpg");
        scan(library.getId());

        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file
                    (library_id, relative_path, size_bytes, mtime, extension, status,
                     first_seen_at, last_seen_at)
                VALUES (?, '未索引.cbz', 100, now(), 'cbz', 'ACTIVE', now(), now())
                RETURNING id
                """, Long.class, library.getId());
        jdbc.update("""
                INSERT INTO image_node
                    (library_id, domain, materialized_path, sort_path, depth, name, sort_key,
                     source_kind, archive_scanned_file_id)
                VALUES (?, 'IMAGE', '/', '/', 0, '未索引', '未索引', 'ARCHIVE', ?)
                """, library.getId(), scannedId);

        finalizer.onScanCompleted(new LibraryScanCompleted(library.getId(), 0, 0, 0, 0));

        // 索引任务还没跑的压缩包页数就是 0，把它当空节点删掉等于把书弄丢了
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .contains("未索引");
    }

    @Test
    void recalculationSkipsVideoLibraries() {
        MediaLibrary videoLibrary = libraryService.create(
                "视频库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());

        // 视频库的扫描完成事件不应让图片域做任何事
        finalizer.onScanCompleted(new LibraryScanCompleted(videoLibrary.getId(), 0, 0, 0, 0));

        assertThat(catalogService.findRoots(videoLibrary.getId())).isEmpty();
    }
}