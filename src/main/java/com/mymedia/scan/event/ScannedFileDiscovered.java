package com.mymedia.scan.event;

import com.mymedia.scan.spi.MediaKind;

/** A physical file that was not previously recorded. */
public record ScannedFileDiscovered(
        Long scannedFileId,
        Long libraryId,
        String relativePath,
        MediaKind kind) {
}
