package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.jobs.JobQueue;
import com.mymedia.jobs.JobStatus;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "mymedia.jobs.enabled=true",
        "mymedia.jobs.poll-interval=PT1H",
        "mymedia.preview.wiring-enabled=false"
})
class VideoContentBuilderTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    JobQueue jobQueue;

    @Autowired
    LibraryService libraryService;

    @Autowired
    VideoCatalogService catalogService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
    }

    private void writeMedia(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content-" + relative);
    }

    private void scan(Long libraryId) {
        Long jobId = scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    void movieBecomesFlatItemWithOneFile() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/黑客帝国 (1999).mkv");

        scan(library.getId());

        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        VideoItem item = items.getFirst();
        assertThat(item.getTitle()).isEqualTo("黑客帝国");
        assertThat(item.getStructure()).isEqualTo(VideoStructure.FLAT);
        assertThat(catalogService.filesOf(item.getId())).hasSize(1);
    }

    @Test
    void seriesEpisodesGroupIntoOneItemWithSeasons() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("番剧/进击的巨人/S01E01.mkv");
        writeMedia("番剧/进击的巨人/S01E02.mkv");
        writeMedia("番剧/进击的巨人/S02E01.mkv");

        scan(library.getId());

        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);

        VideoItem item = items.getFirst();
        assertThat(item.getTitle()).isEqualTo("进击的巨人");
        // 出现季号即提升为 GROUPED
        assertThat(item.getStructure()).isEqualTo(VideoStructure.GROUPED);
        assertThat(catalogService.groupsOf(item.getId())).hasSize(2);
        assertThat(catalogService.filesOf(item.getId())).hasSize(3);
    }

    @Test
    void episodesAreOrderedNaturally() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("番剧/某番/S01E01.mkv");
        writeMedia("番剧/某番/S01E10.mkv");
        writeMedia("番剧/某番/S01E02.mkv");

        scan(library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        Long seasonOne = catalogService.groupsOf(item.getId()).getFirst().getId();

        assertThat(catalogService.episodesOf(seasonOne))
                .extracting(VideoFile::getEpisodeIndex)
                .containsExactly(1, 2, 10);
    }

    @Test
    void unparsableFileStillBecomesAnItem() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("随手录的一段.mkv");

        scan(library.getId());

        // 解析不出任何模式也必须可用 —— 标题回落到文件名
        List<VideoItem> items = catalogService.findByLibrary(library.getId());
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getTitle()).isEqualTo("随手录的一段");
        assertThat(items.getFirst().getItemType()).isEqualTo(VideoItemType.SINGLE_VIDEO);
    }

    @Test
    void rescanIsIdempotent() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");

        scan(library.getId());
        scan(library.getId());
        scan(library.getId());

        // 重复扫描不应产生重复条目
        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        List<VideoFile> files = catalogService.filesOf(item.getId());
        assertThat(files).hasSize(1);
        assertThat(files)
                .extracting(VideoFile::getScannedFileId)
                .hasSize(1)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }

    @Test
    void renamingFileKeepsTheSameVideoFileRow() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/旧名.mkv");
        scan(library.getId());

        VideoItem item = catalogService.findByLibrary(library.getId()).getFirst();
        Long videoFileId = catalogService.filesOf(item.getId()).getFirst().getId();

        Files.move(root.resolve("电影/旧名.mkv"), root.resolve("电影/新名.mkv"));
        scan(library.getId());

        // 改名走物理层，语义层通过外键跟随 —— video_file 行不变
        assertThat(catalogService.filesOf(item.getId()))
                .extracting(VideoFile::getId)
                .containsExactly(videoFileId);
    }

    @Test
    void renamingLeavesNoOrphanItem() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/旧名.mkv");
        scan(library.getId());

        Files.move(root.resolve("电影/旧名.mkv"), root.resolve("电影/新名.mkv"));
        scan(library.getId());

        // 扫描先发布「发现新文件」、随后改名配对才把新记录删掉，
        // 这中间已经按新文件名建好了一个条目。配对成功后 video_file 随
        // scanned_file 级联删除，那个条目会作为无文件的孤儿留下来。
        // 重命名一部电影不该让列表里多出一条空条目。
        assertThat(catalogService.findByLibrary(library.getId())).hasSize(1);
    }
}
