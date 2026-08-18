package com.mymedia.scan.event;

import java.time.Instant;

/** A physical file whose content metadata changed or whose path reappeared. */
public record ScannedFileChanged(
        Long scannedFileId,
        Long libraryId,
        String relativePath,
        long sizeBytes,
        Instant mtime,
        boolean reactivated) {
}
