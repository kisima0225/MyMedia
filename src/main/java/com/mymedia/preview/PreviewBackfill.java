package com.mymedia.preview;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/** 扫描结束时补排还没有封面的条目。 */
@Component
@ConditionalOnProperty(prefix = "mymedia.preview", name = "wiring-enabled",
        havingValue = "true", matchIfMissing = true)
class PreviewBackfill {

    private static final Logger log = LoggerFactory.getLogger(PreviewBackfill.class);
    private static final int BATCH_LIMIT = 500;

    private final LibraryService libraryService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final PreviewTrigger trigger;

    PreviewBackfill(LibraryService libraryService,
                    VideoCatalogService videoCatalog,
                    ImageCatalogService imageCatalog,
                    PreviewTrigger trigger) {
        this.libraryService = libraryService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.trigger = trigger;
    }

    /** 使用新事务写入 job，避免参与已经完成提交的扫描事务。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onScanCompleted(LibraryScanCompleted event) {
        LibraryDomain domain = libraryService.getById(event.libraryId()).getDomain();
        int queued = switch (domain) {
            case VIDEO -> backfillVideo(event.libraryId());
            case IMAGE -> backfillImage(event.libraryId());
        };
        if (queued > 0) {
            log.info("扫描完成后补排预览生成 libraryId={} 数量={}", event.libraryId(), queued);
        }
    }

    private int backfillVideo(Long libraryId) {
        List<Long> itemIds = videoCatalog.itemsWithoutCover(libraryId, BATCH_LIMIT);
        int queued = 0;
        for (Long itemId : itemIds) {
            for (VideoFile file : videoCatalog.filesOf(itemId)) {
                trigger.requestVideoPreview(file.getId());
                queued++;
            }
        }
        return queued;
    }

    private int backfillImage(Long libraryId) {
        List<Long> nodeIds = imageCatalog.nodesWithoutCover(libraryId, BATCH_LIMIT);
        nodeIds.forEach(trigger::requestImagePreview);
        return nodeIds.size();
    }
}
