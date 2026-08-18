package com.mymedia.scan.event;

/** 一个媒体库的扫描已结束。 */
public record LibraryScanCompleted(
        Long libraryId,
        int added,
        int updated,
        int vanished,
        int relocated) {
}
