package com.mymedia.scan;

import com.mymedia.scan.event.ScannedFileRelocated;
import com.mymedia.shared.SampledHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Detects renames and moves by pairing missing and newly added files by sampled hash. */
@Service
class RelocationDetector {

    private static final Logger log = LoggerFactory.getLogger(RelocationDetector.class);

    private final ScannedFileRepository repository;

    RelocationDetector(ScannedFileRepository repository) {
        this.repository = repository;
    }

    /**
     * Pairs only files that became missing during the current reconciliation.
     *
     * @param newlyAddedIds ids inserted by the current reconciliation
     * @param newlyVanishedIds ids that were ACTIVE before this scan and MISSING after reconciliation
     * @return the relocation events for pairs applied by this transaction
     */
    @Transactional
    List<ScannedFileRelocated> detectAndApply(Long libraryId, List<Long> newlyAddedIds,
                                              List<Long> newlyVanishedIds) {
        if (newlyAddedIds.isEmpty() || newlyVanishedIds.isEmpty()) {
            return List.of();
        }

        List<ScannedFile> missing = repository.findAllById(newlyVanishedIds).stream()
                .filter(file -> libraryId.equals(file.getLibraryId()))
                .filter(file -> file.getStatus() == ScannedFileStatus.MISSING)
                .toList();
        if (missing.isEmpty()) {
            return List.of();
        }

        Map<Long, List<ScannedFile>> missingBySize = new HashMap<>();
        for (ScannedFile file : missing) {
            missingBySize.computeIfAbsent(file.getSizeBytes(), ignored -> new ArrayList<>())
                    .add(file);
        }

        List<ScannedFile> added = repository.findAllById(newlyAddedIds);
        Instant now = Instant.now();
        List<ScannedFileRelocated> relocated = new ArrayList<>();

        for (ScannedFile candidate : added) {
            List<ScannedFile> sameSize = missingBySize.get(candidate.getSizeBytes());
            if (sameSize == null || sameSize.isEmpty()) {
                continue;
            }

            String candidateHash = candidate.getContentHash();
            if (candidateHash == null) {
                continue;
            }

            ScannedFile match = null;
            for (ScannedFile gone : sameSize) {
                if (candidateHash.equals(gone.getContentHash())) {
                    match = gone;
                    break;
                }
            }
            if (match == null) {
                continue;
            }

            String oldPath = match.getRelativePath();
            String newPath = candidate.getRelativePath();
            Long survivingId = match.getId();

            repository.delete(candidate);
            repository.flush();
            match.relocateTo(newPath, now);
            match.assignContentHash(candidateHash);

            sameSize.remove(match);
            log.info("识别到文件移动: {} -> {} (id={})", oldPath, newPath, survivingId);
            relocated.add(new ScannedFileRelocated(survivingId, libraryId, oldPath, newPath));
        }
        return relocated;
    }

    /** Computes the hash outside a database transaction, then persists it by id. */
    void ensureHash(Path libraryRoot, ScannedFile file) {
        if (file.getContentHash() != null) {
            return;
        }
        String hash = hashOf(libraryRoot, file);
        if (hash != null) {
            repository.updateContentHash(file.getId(), hash);
        }
    }

    private String hashOf(Path libraryRoot, ScannedFile file) {
        try {
            return SampledHash.of(libraryRoot.resolve(file.getRelativePath()), file.getSizeBytes());
        } catch (IOException e) {
            log.warn("计算内容哈希失败，跳过改名检测: {}", file.getRelativePath(), e);
            return null;
        }
    }
}
