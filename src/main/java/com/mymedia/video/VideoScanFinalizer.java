package com.mymedia.video;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次扫描结束后回收无文件的孤儿条目。
 *
 * <p>文件改名配对会删除新路径对应的临时 {@code scanned_file}，其级联删除
 * {@code video_file} 后可能留下没有文件的语义条目；这里只清理真正没有任何
 * {@code video_file} 的视频条目。物理文件标记为 {@code MISSING} 不会触发清理。
 */
@Component
class VideoScanFinalizer {

    private static final Logger log = LoggerFactory.getLogger(VideoScanFinalizer.class);

    private final LibraryService libraryService;
    private final JdbcTemplate jdbc;

    VideoScanFinalizer(LibraryService libraryService, JdbcTemplate jdbc) {
        this.libraryService = libraryService;
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional
    void onScanCompleted(LibraryScanCompleted event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.VIDEO) {
            return;
        }

        int removed = jdbc.update("""
                DELETE FROM video_item i
                WHERE i.library_id = ?
                  AND NOT EXISTS (SELECT 1 FROM video_file f WHERE f.item_id = i.id)
                """, event.libraryId());
        if (removed > 0) {
            log.info("回收无文件的孤儿视频条目 libraryId={} count={}", event.libraryId(), removed);
        }
    }
}
