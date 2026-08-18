package com.mymedia.scan;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reconciles one directory traversal with the physical-file records. */
@Service
class ScanReconciler {

    private final ScannedFileRepository repository;

    ScanReconciler(ScannedFileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    ScanOutcome reconcile(Long libraryId, List<ScannedEntry> entries) {
        Instant scanTime = Instant.now();
        Map<String, ScannedFile> existingByPath = new HashMap<>();
        for (ScannedFile file : repository.findByLibraryId(libraryId)) {
            existingByPath.put(file.getRelativePath(), file);
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;

        for (ScannedEntry entry : entries) {
            ScannedFile existing = existingByPath.remove(entry.relativePath());
            if (existing == null) {
                repository.saveAndFlush(new ScannedFile(
                        libraryId, entry.relativePath(), entry.sizeBytes(),
                        entry.mtime(), entry.extension()));
                added++;
            } else if (isContentChanged(existing, entry)) {
                existing.updateContent(entry.sizeBytes(), entry.mtime(), scanTime);
                updated++;
            } else {
                existing.touch(scanTime);
                unchanged++;
            }
        }

        int vanished = 0;
        for (ScannedFile leftover : existingByPath.values()) {
            if (leftover.getStatus() == ScannedFileStatus.ACTIVE) {
                leftover.markMissing();
                vanished++;
            }
        }

        return new ScanOutcome(added, updated, unchanged, vanished, 0);
    }

    /** Size or modification time changes invalidate the stored content hash. */
    private static boolean isContentChanged(ScannedFile existing, ScannedEntry entry) {
        return existing.getSizeBytes() != entry.sizeBytes()
                || !existing.getMtime().equals(entry.mtime());
    }
}
