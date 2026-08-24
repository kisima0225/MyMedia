package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;

import java.time.Instant;

/** {@code scrape_candidate} 的一行。 */
public record ScrapeCandidateRecord(
        Long id,
        LibraryDomain domain,
        Long targetId,
        String provider,
        String externalId,
        String title,
        Integer year,
        double score,
        String payload,
        Instant createdAt) {
}
