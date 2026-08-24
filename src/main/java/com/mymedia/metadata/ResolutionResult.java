package com.mymedia.metadata;

import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;

import java.util.List;

/**
 * 一次刮削的结论。
 *
 * @param status     写回条目的 {@code scrape_status}
 * @param patch      要应用的数据；{@code NEEDS_REVIEW} 与 {@code ERROR} 时为 {@code null}
 * @param candidates 要写进 {@code scrape_candidate} 的候选；只有 {@code NEEDS_REVIEW} 时非空
 */
record ResolutionResult(ScrapeStatus status, MetadataPatch patch, List<MetadataCandidate> candidates) {

    static ResolutionResult matched(MetadataPatch patch) {
        return new ResolutionResult(ScrapeStatus.MATCHED, patch, List.of());
    }

    static ResolutionResult needsReview(List<MetadataCandidate> candidates) {
        return new ResolutionResult(ScrapeStatus.NEEDS_REVIEW, null, candidates);
    }

    static ResolutionResult noMatch(MetadataPatch fallbackPatch) {
        return new ResolutionResult(ScrapeStatus.NO_MATCH, fallbackPatch, List.of());
    }

    static ResolutionResult error() {
        return new ResolutionResult(ScrapeStatus.ERROR, null, List.of());
    }
}
