package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.LibraryScanCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图片库扫描收尾的唯一入口。
 *
 * <p>顺序是有意义的：<b>先把改名与移动落实到节点树，再重算页序与计数</b>。
 * 反过来会先给一堆即将被回收的空壳节点算一遍数。
 *
 * <p>把顺序固定在一个方法里，而不是靠两个监听器加 {@code @Order}——
 * 后者的顺序是隐式的，很容易在某次重构里被悄悄改掉。
 */
@Component
class ImageScanFinalizer {

    private final LibraryService libraryService;
    private final ImageTreeRelocator relocator;
    private final ImageLibraryRecalculator recalculator;

    ImageScanFinalizer(LibraryService libraryService,
                       ImageTreeRelocator relocator,
                       ImageLibraryRecalculator recalculator) {
        this.libraryService = libraryService;
        this.relocator = relocator;
        this.recalculator = recalculator;
    }

    @EventListener
    void onScanCompleted(LibraryScanCompleted event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        relocator.applyPending(event.libraryId());
        recalculator.recalculate(event.libraryId());
    }
}