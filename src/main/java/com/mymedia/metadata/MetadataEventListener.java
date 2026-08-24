package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 新条目排刮削任务；扫描结束时把还是 {@code PENDING} 的条目补排一遍。
 *
 * <p>监听器固定在提交后执行，并用新事务持久化任务，保证扫描事务提交后才排队。
 */
@Component
class MetadataEventListener {

    private static final Logger log = LoggerFactory.getLogger(MetadataEventListener.class);

    private static final int BATCH_LIMIT = 500;

    private final LibraryService libraryService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final MetadataTrigger trigger;

    MetadataEventListener(LibraryService libraryService,
                          VideoCatalogService videoCatalog,
                          ImageCatalogService imageCatalog,
                          MetadataTrigger trigger) {
        this.libraryService = libraryService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.trigger = trigger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onVideoItemCreated(VideoItemCreated event) {
        if (scrapingDisabled(event.libraryId())) {
            videoCatalog.updateScrapeStatus(event.itemId(), ScrapeStatus.NOT_APPLICABLE);
            return;
        }
        trigger.request(LibraryDomain.VIDEO, event.itemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onImageNodeCreated(ImageNodeCreated event) {
        if (scrapingDisabled(event.libraryId())) {
            imageCatalog.updateScrapeStatus(event.nodeId(), ScrapeStatus.NOT_APPLICABLE);
            return;
        }
        trigger.request(LibraryDomain.IMAGE, event.nodeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onScanCompleted(LibraryScanCompleted event) {
        if (scrapingDisabled(event.libraryId())) {
            return;
        }
        LibraryDomain domain = libraryService.getById(event.libraryId()).getDomain();
        List<Long> pending = switch (domain) {
            case VIDEO -> videoCatalog.itemsPendingScrape(event.libraryId(), BATCH_LIMIT);
            case IMAGE -> imageCatalog.nodesPendingScrape(event.libraryId(), BATCH_LIMIT);
        };
        pending.forEach(targetId -> trigger.request(domain, targetId));
        if (!pending.isEmpty()) {
            log.info("扫描完成后补排刮削 libraryId={} 数量={}", event.libraryId(), pending.size());
        }
    }

    private boolean scrapingDisabled(Long libraryId) {
        return libraryService.metadataProvidersOf(libraryId).isEmpty();
    }
}
