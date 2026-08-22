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
        "mymedia.jobs.poll-interval=PT1H"
})
class ImageTreeRelocatorTest extends AbstractIntegrationTest {

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

    private void writeImage(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "img-" + UUID.randomUUID());
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

    private ImageNode rootNamed(MediaLibrary library, String name) {
        return catalogService.findRoots(library.getId()).stream()
                .filter(node -> node.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("没有顶层节点: " + name));
    }

    @Test
    void renamingADirectoryKeepsTheSameNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作者A/001.jpg");
        scan(library.getId());

        ImageNode before = rootNamed(library, "作者A");
        Long pageId = catalogService.pagesOf(before.getId()).getFirst().getId();

        Files.move(root.resolve("作者A"), root.resolve("作者B"));
        scan(library.getId());

        ImageNode after = rootNamed(library, "作者B");
        // 节点 id 不变 —— 挂在它上面的阅读进度、收藏、阅读模式覆盖全部保住
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(catalogService.pagesOf(after.getId()))
                .extracting(ImageFile::getId).containsExactly(pageId);
    }

    @Test
    void renamingLeavesNoOrphanNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("作者A/001.jpg");
        scan(library.getId());

        Files.move(root.resolve("作者A"), root.resolve("作者B"));
        scan(library.getId());

        // 扫描过程中会瞬时造出一个「作者B」空壳，收尾时必须只剩一个节点
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("作者B");
    }

    @Test
    void movingADirectoryRewritesTheWholeSubtree() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("旧家/系列X/001.jpg");
        writeImage("旧家/系列X/子章/002.jpg");
        writeImage("新家/占位.jpg");
        scan(library.getId());

        ImageNode seriesBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "旧家").getId()).getFirst();
        ImageNode chapterBefore = browseService
                .childNodes(library.getId(), seriesBefore.getId()).getFirst();

        Files.move(root.resolve("旧家/系列X"), root.resolve("新家/系列X"));
        scan(library.getId());

        ImageNode seriesAfter = catalogService.getNode(seriesBefore.getId());
        ImageNode chapterAfter = catalogService.getNode(chapterBefore.getId());
        ImageNode newHome = rootNamed(library, "新家");

        // 节点 id 全部不变，但整棵子树的物化路径被一条 UPDATE 重写到新父之下
        assertThat(seriesAfter.getParentId()).isEqualTo(newHome.getId());
        assertThat(seriesAfter.getMaterializedPath()).startsWith(newHome.getMaterializedPath());
        assertThat(chapterAfter.getMaterializedPath()).startsWith(seriesAfter.getMaterializedPath());
        assertThat(chapterAfter.getDepth()).isEqualTo(seriesAfter.getDepth() + 1);
    }

    @Test
    void movingASingleFileOnlyReattachesThatPage() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("甲/001.jpg");
        writeImage("甲/002.jpg");
        writeImage("乙/003.jpg");
        scan(library.getId());

        ImageNode a = rootNamed(library, "甲");
        ImageNode b = rootNamed(library, "乙");

        Files.move(root.resolve("甲/001.jpg"), root.resolve("乙/001.jpg"));
        scan(library.getId());

        // 只搬走一个文件不是目录移动，甲必须原地不动
        assertThat(catalogService.getNode(a.getId()).getName()).isEqualTo("甲");
        assertThat(catalogService.pagesOf(a.getId())).hasSize(1);
        assertThat(catalogService.pagesOf(b.getId())).hasSize(2);
    }

    @Test
    void renamingAnArchiveKeepsItsNodeAndPages() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("漫画/vol01.cbz", "001.jpg", "002.jpg");
        scan(library.getId());

        ImageNode comics = rootNamed(library, "漫画");
        ImageNode volumeBefore = browseService.childNodes(library.getId(), comics.getId()).getFirst();
        List<Long> pageIdsBefore = catalogService.pagesOf(volumeBefore.getId())
                .stream().map(ImageFile::getId).toList();

        Files.move(root.resolve("漫画/vol01.cbz"), root.resolve("漫画/第01卷.cbz"));
        scan(library.getId());

        ImageNode volumeAfter = catalogService.getNode(volumeBefore.getId());
        assertThat(volumeAfter.getName()).isEqualTo("第01卷");
        assertThat(catalogService.pagesOf(volumeAfter.getId()))
                .extracting(ImageFile::getId)
                .containsExactlyElementsOf(pageIdsBefore);
    }

    @Test
    void movingAnArchiveToAnotherDirectoryMovesItsNode() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeArchive("待整理/vol01.cbz", "001.jpg");
        writeImage("已整理/占位.jpg");
        scan(library.getId());

        ImageNode volumeBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "待整理").getId()).getFirst();

        Files.move(root.resolve("待整理/vol01.cbz"), root.resolve("已整理/vol01.cbz"));
        scan(library.getId());

        ImageNode sorted = rootNamed(library, "已整理");
        assertThat(catalogService.getNode(volumeBefore.getId()).getParentId())
                .isEqualTo(sorted.getId());
    }

    @Test
    void movingTheParentAlsoCarriesNestedDirectories() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeImage("顶层/中层/底层/001.jpg");
        scan(library.getId());

        ImageNode middleBefore = browseService
                .childNodes(library.getId(), rootNamed(library, "顶层").getId()).getFirst();
        ImageNode bottomBefore = browseService
                .childNodes(library.getId(), middleBefore.getId()).getFirst();

        Files.move(root.resolve("顶层"), root.resolve("顶层改"));
        scan(library.getId());

        // 顶层改名，中层与底层的节点 id 与父子关系都不受影响
        assertThat(catalogService.getNode(middleBefore.getId()).getName()).isEqualTo("中层");
        assertThat(catalogService.getNode(bottomBefore.getId()).getParentId())
                .isEqualTo(middleBefore.getId());
        assertThat(catalogService.findRoots(library.getId()))
                .extracting(ImageNode::getName)
                .containsExactly("顶层改");
    }
}
