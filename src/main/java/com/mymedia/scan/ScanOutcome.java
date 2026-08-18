package com.mymedia.scan;

/** Statistics for one reconciliation pass. */
record ScanOutcome(int added, int updated, int unchanged, int vanished, int relocated) {

    static ScanOutcome empty() {
        return new ScanOutcome(0, 0, 0, 0, 0);
    }

    ScanOutcome withRelocated(int count) {
        return new ScanOutcome(added, updated, unchanged,
                Math.max(0, vanished - count), count);
    }
}
