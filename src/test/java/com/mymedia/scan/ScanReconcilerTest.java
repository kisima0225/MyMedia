package com.mymedia.scan;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.spi.MediaKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScanReconcilerTest extends AbstractIntegrationTest {

    @Autowired
    ScanReconciler reconciler;

    @Autowired
    ScannedFileQueryService queryService;

    @Autowired
    LibraryService libraryService;

    private MediaLibrary newLibrary() {
        return libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/media/" + UUID.randomUUID());
    }

    private ScannedEntry entry(String path, long size, Instant mtime) {
        return new ScannedEntry(path, size, mtime, "mkv", MediaKind.VIDEO);
    }

    @Test
    void firstScanAddsEverything() {
        MediaLibrary library = newLibrary();
        Instant now = Instant.now();

        ScanOutcome outcome = reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 100, now), entry("b.mkv", 200, now)));

        assertThat(outcome.added()).isEqualTo(2);
        assertThat(outcome.vanished()).isZero();
        assertThat(queryService.countActive(library.getId())).isEqualTo(2L);
    }

    @Test
    void unchangedFilesAreNotTouched() {
        MediaLibrary library = newLibrary();
        Instant mtime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        reconciler.reconcile(library.getId(), List.of(entry("a.mkv", 100, mtime)));

        ScanOutcome second = reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 100, mtime)));

        assertThat(second.added()).isZero();
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.updated()).isZero();
    }

    @Test
    void subMicrosecondMtimeRemainsUnchangedAfterPersistence() {
        MediaLibrary library = newLibrary();
        Instant mtime = Instant.parse("2026-08-18T00:00:00.123456789Z");
        reconciler.reconcile(library.getId(), List.of(entry("a.mkv", 100, mtime)));

        ScanOutcome second = reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 100, mtime)));

        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.updated()).isZero();
    }

    @Test
    void changedSizeMarksFileUpdated() {
        MediaLibrary library = newLibrary();
        Instant mtime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        reconciler.reconcile(library.getId(), List.of(entry("a.mkv", 100, mtime)));
        Long originalId = queryService.findByPath(library.getId(), "a.mkv").orElseThrow().getId();
        Instant changedMtime = mtime.plusSeconds(1);

        ScanOutcome second = reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 999, changedMtime)));

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.changedIds()).containsExactly(originalId);
        assertThat(second.reactivatedIds()).isEmpty();
        assertThat(queryService.findByPath(library.getId(), "a.mkv").orElseThrow())
                .satisfies(file -> {
                    assertThat(file.getSizeBytes()).isEqualTo(999L);
                    assertThat(file.getMtime()).isEqualTo(ScannedFile.toPostgresPrecision(changedMtime));
                });
    }

    @Test
    void vanishedFileIsMarkedMissingNotDeleted() {
        MediaLibrary library = newLibrary();
        Instant now = Instant.now();
        reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 100, now), entry("b.mkv", 200, now)));

        ScanOutcome second = reconciler.reconcile(library.getId(),
                List.of(entry("a.mkv", 100, now)));

        assertThat(second.vanished()).isEqualTo(1);

        var gone = queryService.findByPath(library.getId(), "b.mkv");
        assertThat(gone).isPresent();
        assertThat(gone.get().getStatus()).isEqualTo(ScannedFileStatus.MISSING);
    }

    @Test
    void reappearingFileIsReactivated() {
        MediaLibrary library = newLibrary();
        Instant now = Instant.now();
        reconciler.reconcile(library.getId(), List.of(entry("a.mkv", 100, now)));
        Long originalId = queryService.findByPath(library.getId(), "a.mkv").orElseThrow().getId();

        reconciler.reconcile(library.getId(), List.of());
        ScanOutcome recovery = reconciler.reconcile(library.getId(), List.of(entry("a.mkv", 100, now)));

        var file = queryService.findByPath(library.getId(), "a.mkv").orElseThrow();
        assertThat(file.getStatus()).isEqualTo(ScannedFileStatus.ACTIVE);
        assertThat(file.getId()).isEqualTo(originalId);
        assertThat(recovery.changedIds()).isEmpty();
        assertThat(recovery.reactivatedIds()).containsExactly(originalId);
    }

    @Test
    void reconcilingOneLibraryDoesNotAffectAnother() {
        MediaLibrary a = newLibrary();
        MediaLibrary b = newLibrary();
        Instant now = Instant.now();
        reconciler.reconcile(a.getId(), List.of(entry("x.mkv", 1, now)));
        reconciler.reconcile(b.getId(), List.of(entry("y.mkv", 1, now)));

        reconciler.reconcile(a.getId(), List.of());

        assertThat(queryService.countActive(a.getId())).isZero();
        assertThat(queryService.countActive(b.getId())).isEqualTo(1L);
    }
}
