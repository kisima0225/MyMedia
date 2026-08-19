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
        "mymedia.jobs.poll-interval=PT1H"
})
class VideoFolderIndexerTest extends AbstractIntegrationTest {

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
    VideoBrowseService browseService;

    private MediaLibrary libraryAtRoot() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
    }

    private void writeMedia(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "c-" + relative);
    }

    private void scan(Long libraryId) {
        Long jobId = scanTrigger.requestScan(libraryId);
        jobPoller.pollOnce();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }

    @Test
    void buildsFolderTreeMirroringDirectories() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/科幻/黑客帝国.mkv");
        writeMedia("电影/动作/谍影重重.mkv");
        writeMedia("番剧/进击的巨人/S01E01.mkv");

        scan(library.getId());

        List<VideoFolder> topLevel = browseService.childFolders(library.getId(), null);
        assertThat(topLevel).extracting(VideoFolder::getName)
                .containsExactlyInAnyOrder("电影", "番剧");

        VideoFolder movies = topLevel.stream()
                .filter(f -> f.getName().equals("电影")).findFirst().orElseThrow();
        assertThat(movies.getDirectItemCount()).isZero();
        assertThat(movies.getTotalItemCount()).isEqualTo(2);
        assertThat(browseService.childFolders(library.getId(), movies.getId()))
                .extracting(VideoFolder::getName)
                .containsExactlyInAnyOrder("科幻", "动作");
        assertThat(browseService.childFolders(library.getId(), movies.getId()))
                .allSatisfy(folder -> assertThat(folder.getDirectItemCount()).isEqualTo(1));
    }

    @Test
    void materializedPathReflectsHierarchy() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("a/b/c/片子.mkv");

        scan(library.getId());

        VideoFolder a = browseService.childFolders(library.getId(), null).getFirst();
        VideoFolder b = browseService.childFolders(library.getId(), a.getId()).getFirst();
        VideoFolder c = browseService.childFolders(library.getId(), b.getId()).getFirst();

        assertThat(a.getDepth()).isEqualTo(1);
        assertThat(b.getDepth()).isEqualTo(2);
        assertThat(c.getDepth()).isEqualTo(3);
        assertThat(c.getMaterializedPath()).startsWith(b.getMaterializedPath());
        assertThat(b.getMaterializedPath()).startsWith(a.getMaterializedPath());
    }

    @Test
    void breadcrumbResolvesAncestorsWithoutRecursiveQuery() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("一层/二层/三层/片子.mkv");

        scan(library.getId());

        VideoFolder l1 = browseService.childFolders(library.getId(), null).getFirst();
        VideoFolder l2 = browseService.childFolders(library.getId(), l1.getId()).getFirst();
        VideoFolder l3 = browseService.childFolders(library.getId(), l2.getId()).getFirst();

        assertThat(browseService.breadcrumb(l3.getId()))
                .extracting(VideoFolder::getName)
                .containsExactly("一层", "二层", "三层");
    }

    @Test
    void itemsAreAttachedToTheirDirectory() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子.mkv");

        scan(library.getId());

        VideoFolder movies = browseService.childFolders(library.getId(), null).getFirst();
        assertThat(browseService.itemsIn(movies.getId()))
                .extracting(VideoItem::getTitle)
                .containsExactly("片子");
    }

    @Test
    void foldersAreSortedNaturally() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("第10季/a.mkv");
        writeMedia("第2季/a.mkv");
        writeMedia("第1季/a.mkv");

        scan(library.getId());

        assertThat(browseService.childFolders(library.getId(), null))
                .extracting(VideoFolder::getName)
                .containsExactly("第1季", "第2季", "第10季");
    }

    @Test
    void rescanDoesNotDuplicateFoldersOrItemCounts() throws IOException {
        MediaLibrary library = libraryAtRoot();
        writeMedia("电影/片子/S01E01.mkv");
        writeMedia("电影/片子/S01E02.mkv");

        scan(library.getId());
        scan(library.getId());

        VideoFolder movies = browseService.childFolders(library.getId(), null).getFirst();
        VideoFolder titleFolder = browseService.childFolders(library.getId(), movies.getId()).getFirst();
        assertThat(browseService.childFolders(library.getId(), null)).hasSize(1);
        assertThat(movies.getDirectItemCount()).isZero();
        assertThat(movies.getTotalItemCount()).isEqualTo(1);
        assertThat(titleFolder.getDirectItemCount()).isEqualTo(1);
        assertThat(titleFolder.getTotalItemCount()).isEqualTo(1);
        assertThat(browseService.itemsIn(titleFolder.getId()))
                .extracting(VideoItem::getTitle)
                .containsExactly("片子");
    }
}
