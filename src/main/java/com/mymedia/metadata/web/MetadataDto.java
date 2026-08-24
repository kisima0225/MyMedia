package com.mymedia.metadata.web;

import com.mymedia.shared.MetadataSnapshot;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.Set;

final class MetadataDto {

    private MetadataDto() {
    }

    record EditRequest(@NotNull Map<String, String> fields) {
    }

    record Response(
            Map<String, String> fields,
            Map<String, String> fieldSources,
            Set<String> lockedFields,
            String scrapeStatus,
            String scrapeSource,
            String scrapeSourceId) {

        static Response from(MetadataSnapshot snapshot) {
            return new Response(
                    snapshot.fields(),
                    snapshot.fieldSources(),
                    snapshot.lockedFields(),
                    snapshot.scrapeStatus().name(),
                    snapshot.scrapeSource(),
                    snapshot.scrapeSourceId());
        }
    }

    record CandidateResponse(
            Long id,
            String provider,
            String externalId,
            String title,
            Integer year,
            double score) {

        static CandidateResponse from(com.mymedia.metadata.ScrapeCandidateRecord record) {
            return new CandidateResponse(record.id(), record.provider(), record.externalId(),
                    record.title(), record.year(), record.score());
        }
    }
}
