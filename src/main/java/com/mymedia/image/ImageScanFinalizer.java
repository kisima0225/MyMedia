package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图片库扫描收尾的唯一入口。
 *
 * <p>把「先处理改名移动、再重算页序与计数」这个顺序固定在一个方法里，
 * 而不是靠两个监听器加 {@code @Order}——后者的顺序是隐式的，很容易在
 * 某次重构里被悄悄改掉。
 *
 * <p>用普通 {@code @EventListener}（同步、同事务）而非
 * {@code @ApplicationModuleListener}（异步）：扫描任务本身就跑在后台线程里，
 * 再异步一层只会让测试变成时序竞猜。
 */
@Component
class ImageScanFinalizer {

    private final LibraryService libraryService;
    private final ImageLibraryRecalculator recalculator;

    ImageScanFinalizer(LibraryService libraryService, ImageLibraryRecalculator recalculator) {
        this.libraryService = libraryService;
        this.recalculator = recalculator;
    }

    @EventListener
    void onScanCompleted(LibraryScanCompleted event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        recalculator.recalculate(event.libraryId());
    }
}