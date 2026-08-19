package com.mymedia.video;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.jobs.JobPoller;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoProgressServiceTest extends AbstractIntegrationTest {

    @TempDir
    Path root;

    @Autowired
    VideoProgressService progressService;

    @Autowired
    ScanTrigger scanTrigger;

    @Autowired
    JobPoller jobPoller;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    VideoCatalogService catalogService;

    @Autowired
    JdbcTemplate jdbc;

    private UserAccount newUser() {
        return registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
    }

    private List<Long> setUpFiles(String... names) throws IOException {
        for (String name : names) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent() == null ? root : file.getParent());
            Files.writeString(file, "c-" + name);
        }
        MediaLibrary library = libraryService.create(
                "库" + UUID.randomUUID(), LibraryDomain.VIDEO, root.toString());
        scanTrigger.requestScan(library.getId());
        jobPoller.pollOnce();

        return catalogService.findByLibrary(library.getId()).stream()
                .flatMap(item -> catalogService.filesOf(item.getId()).stream())
                .map(VideoFile::getId)
                .toList();
    }

    @Test
    void recordsProgressForUser() throws IOException {
        Long fileId = setUpFiles("电影/a.mkv").getFirst();
        UserAccount user = newUser();

        progressService.record(user.getId(), fileId, 120, 3600);

        var progress = progressService.find(user.getId(), fileId).orElseThrow();
        assertThat(progress.getPositionSeconds()).isEqualTo(120);
        assertThat(progress.getDurationSeconds()).isEqualTo(3600);
        assertThat(progress.isCompleted()).isFalse();
    }

    @Test
    void progressIsPerUser() throws IOException {
        Long fileId = setUpFiles("电影/b.mkv").getFirst();
        UserAccount alice = newUser();
        UserAccount bob = newUser();

        progressService.record(alice.getId(), fileId, 120, 3600);
        progressService.record(bob.getId(), fileId, 999, 3600);

        // 用户态数据独立成表，互不干扰 —— 这是多用户设计的核心
        assertThat(progressService.find(alice.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(120);
        assertThat(progressService.find(bob.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(999);
    }

    @Test
    void repeatedRecordUpdatesInPlace() throws IOException {
        Long fileId = setUpFiles("电影/c.mkv").getFirst();
        UserAccount user = newUser();

        progressService.record(user.getId(), fileId, 100, 3600);
        progressService.record(user.getId(), fileId, 200, 3600);
        progressService.record(user.getId(), fileId, 300, 3600);

        assertThat(progressService.find(user.getId(), fileId).orElseThrow()
                .getPositionSeconds()).isEqualTo(300);
    }

    @Test
    void nearEndMarksCompleted() throws IOException {
        Long fileId = setUpFiles("电影/d.mkv").getFirst();
        UserAccount user = newUser();

        // 播到 96% 即视为看完 —— 片尾曲期间用户通常直接关掉
        progressService.record(user.getId(), fileId, 2880, 3000);

        assertThat(progressService.find(user.getId(), fileId).orElseThrow()
                .isCompleted()).isTrue();
    }

    @Test
    void continueWatchingExcludesCompletedAndOrdersByRecency() throws IOException {
        List<Long> fileIds = setUpFiles("电影/e1.mkv", "电影/e2.mkv", "电影/e3.mkv");
        UserAccount user = newUser();

        progressService.record(user.getId(), fileIds.get(0), 100, 3600);
        progressService.record(user.getId(), fileIds.get(1), 3550, 3600);   // 看完了
        progressService.record(user.getId(), fileIds.get(2), 200, 3600);

        List<VideoProgress> continueList = progressService.continueWatching(user.getId(), 10);

        assertThat(continueList).extracting(VideoProgress::getVideoFileId)
                .containsExactly(fileIds.get(2), fileIds.get(0));
    }

    @Test
    void continueWatchingRespectsLimit() throws IOException {
        List<Long> fileIds = setUpFiles("电影/f1.mkv", "电影/f2.mkv", "电影/f3.mkv");
        UserAccount user = newUser();
        for (Long id : fileIds) {
            progressService.record(user.getId(), id, 100, 3600);
        }

        assertThat(progressService.continueWatching(user.getId(), 2)).hasSize(2);
    }

    @Test
    void deletingUserCascadesProgress() throws IOException {
        Long fileId = setUpFiles("电影/g.mkv").getFirst();
        UserAccount user = newUser();
        progressService.record(user.getId(), fileId, 100, 3600);

        assertThat(progressService.find(user.getId(), fileId)).isPresent();

        // 通过 JDBC 直接删 users 行，验证数据库外键级联，而不是只验证查询前的存在性。
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM video_progress WHERE user_id = ? AND video_file_id = ?",
                Integer.class, user.getId(), fileId);
        assertThat(remaining).isZero();
    }
}
