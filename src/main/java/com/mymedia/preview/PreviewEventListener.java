package com.mymedia.preview;

import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 新条目一出现就排预览生成。 */
@Component
@ConditionalOnProperty(prefix = "mymedia.preview", name = "wiring-enabled",
        havingValue = "true", matchIfMissing = true)
class PreviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(PreviewEventListener.class);

    private final VideoCatalogService videoCatalog;
    private final PreviewTrigger trigger;

    PreviewEventListener(VideoCatalogService videoCatalog, PreviewTrigger trigger) {
        this.videoCatalog = videoCatalog;
        this.trigger = trigger;
    }

    /** 扫描事务提交后再入队，worker 才能读到刚创建的视频文件。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onVideoItemCreated(VideoItemCreated event) {
        for (VideoFile file : videoCatalog.filesOf(event.itemId())) {
            trigger.requestVideoPreview(file.getId());
        }
        log.debug("已为条目 {} 的文件排队预览生成", event.itemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onImageNodeCreated(ImageNodeCreated event) {
        trigger.requestImagePreview(event.nodeId());
    }
}
