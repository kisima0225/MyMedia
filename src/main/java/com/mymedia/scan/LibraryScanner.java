package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.event.LibraryScanCompleted;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Orchestrates traversal, reconciliation, relocation detection, and event dispatch. */
@Service
class LibraryScanner {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanner.class);

    private final LibraryService libraryService;
    private final ScanReconciler reconciler;
    private final RelocationDetector relocationDetector;
    private final ScannedFileRepository repository;
    private final ApplicationEventPublisher events;
    private final int maxDepth;

    LibraryScanner(LibraryService libraryService,
                   ScanReconciler reconciler,
                   RelocationDetector relocationDetector,
                   ScannedFileRepository repository,
                   ApplicationEventPublisher events,
                   @Value("${mymedia.scan.max-depth:32}") int maxDepth) {
        this.libraryService = libraryService;
        this.reconciler = reconciler;
        this.relocationDetector = relocationDetector;
        this.repository = repository;
        this.events = events;
        this.maxDepth = maxDepth;
    }

    @Transactional
    ScanOutcome scan(Long libraryId) {
        MediaLibrary library = libraryService.getById(libraryId);
        Path root = Path.of(library.getRootPath());
        log.info("开始扫描媒体库 id={} path={}", libraryId, root);

        List<ScannedEntry> entries;
        try {
            entries = new DirectoryWalker(maxDepth).walk(root);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描媒体库失败: " + root, e);
        }

        List<ScannedFile> beforeAll = repository.findByLibraryId(libraryId);
        Set<Long> beforeIds = beforeAll.stream()
                .map(ScannedFile::getId)
                .collect(Collectors.toSet());
        Set<Long> beforeActiveIds = beforeAll.stream()
                .filter(file -> file.getStatus() == ScannedFileStatus.ACTIVE)
                .map(ScannedFile::getId)
                .collect(Collectors.toSet());

        ScanOutcome outcome = reconciler.reconcile(libraryId, entries);

        List<ScannedFile> afterReconcile = repository.findByLibraryId(libraryId);
        List<Long> newlyAddedIds = afterReconcile.stream()
                .map(ScannedFile::getId)
                .filter(id -> !beforeIds.contains(id))
                .toList();

        for (ScannedFile file : afterReconcile) {
            if (file.getStatus() == ScannedFileStatus.ACTIVE) {
                relocationDetector.ensureHash(root, file);
            }
        }

        int relocated = relocationDetector.detectAndApply(libraryId, root, newlyAddedIds);
        ScanOutcome finalOutcome = outcome.withRelocated(relocated);
        publishFinalEvents(libraryId, entries, beforeActiveIds, newlyAddedIds);

        log.info("扫描完成 id={} 新增={} 更新={} 未变={} 消失={} 移动={}",
                libraryId, finalOutcome.added(), finalOutcome.updated(),
                finalOutcome.unchanged(), finalOutcome.vanished(), finalOutcome.relocated());

        events.publishEvent(new LibraryScanCompleted(
                libraryId, finalOutcome.added(), finalOutcome.updated(),
                finalOutcome.vanished(), finalOutcome.relocated()));

        return finalOutcome;
    }

    private void publishFinalEvents(Long libraryId, List<ScannedEntry> entries,
                                    Set<Long> beforeActiveIds, List<Long> newlyAddedIds) {
        Map<String, ScannedEntry> entriesByPath = new HashMap<>();
        for (ScannedEntry entry : entries) {
            entriesByPath.put(entry.relativePath(), entry);
        }
        Set<Long> newlyAddedIdSet = new HashSet<>(newlyAddedIds);
        List<ScannedFile> finalFiles = repository.findByLibraryId(libraryId);

        for (ScannedFile file : finalFiles) {
            if (newlyAddedIdSet.contains(file.getId())
                    && file.getStatus() == ScannedFileStatus.ACTIVE) {
                ScannedEntry entry = entriesByPath.get(file.getRelativePath());
                if (entry != null) {
                    events.publishEvent(new ScannedFileDiscovered(
                            file.getId(), libraryId, file.getRelativePath(), entry.kind()));
                }
            }
        }

        for (ScannedFile file : finalFiles) {
            if (beforeActiveIds.contains(file.getId())
                    && file.getStatus() == ScannedFileStatus.MISSING) {
                events.publishEvent(new ScannedFileVanished(
                        file.getId(), libraryId, file.getRelativePath()));
            }
        }
    }
}
