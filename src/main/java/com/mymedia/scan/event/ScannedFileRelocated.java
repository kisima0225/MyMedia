package com.mymedia.scan.event;

/** 某个物理文件被改名或移动，但内容未变。 */
public record ScannedFileRelocated(
        Long scannedFileId,
        Long libraryId,
        String oldPath,
        String newPath) {
}
