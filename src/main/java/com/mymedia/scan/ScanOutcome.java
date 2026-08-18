package com.mymedia.scan;

import java.util.List;

/** Statistics for one reconciliation pass. */
record ScanOutcome(int added, int updated, int unchanged, int vanished, int relocated,
                   List<Long> changedIds, List<Long> reactivatedIds) {

    ScanOutcome {
        changedIds = List.copyOf(changedIds);
        reactivatedIds = List.copyOf(reactivatedIds);
    }

    static ScanOutcome empty() {
        return new ScanOutcome(0, 0, 0, 0, 0, List.of(), List.of());
    }

    ScanOutcome withRelocated(int count) {
        return new ScanOutcome(added, updated, unchanged,
                Math.max(0, vanished - count), count, changedIds, reactivatedIds);
    }
}
