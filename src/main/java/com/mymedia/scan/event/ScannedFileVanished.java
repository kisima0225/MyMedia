package com.mymedia.scan.event;

/** A physical file that was marked missing during the current scan. */
public record ScannedFileVanished(
        Long scannedFileId,
        Long libraryId,
        String relativePath) {
}
