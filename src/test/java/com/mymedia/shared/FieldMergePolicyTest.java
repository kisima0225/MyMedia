package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FieldMergePolicyTest {

    private static Map<String, String> incoming() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(MetadataFields.TITLE, "进击的巨人");
        fields.put(MetadataFields.SUMMARY, "巨人出现了");
        fields.put(MetadataFields.RATING, "8.4");
        return fields;
    }

    @Test
    void keepsEverythingWhenNothingIsLocked() {
        assertThat(FieldMergePolicy.apply(incoming(), Set.of()))
                .containsOnlyKeys(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING);
    }

    @Test
    void dropsLockedFieldsSoScrapingCannotOverwriteUserEdits() {
        Map<String, String> merged = FieldMergePolicy.apply(
                incoming(), List.of(MetadataFields.TITLE));

        assertThat(merged).doesNotContainKey(MetadataFields.TITLE);
        assertThat(merged).containsEntry(MetadataFields.SUMMARY, "巨人出现了");
    }

    @Test
    void dropsBlankValuesSoAnEmptyScrapeResultDoesNotWipeGoodData() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(MetadataFields.TITLE, "  ");
        fields.put(MetadataFields.SUMMARY, null);
        fields.put(MetadataFields.RATING, "7.0");

        assertThat(FieldMergePolicy.apply(fields, Set.of()))
                .containsExactly(Map.entry(MetadataFields.RATING, "7.0"));
    }

    @Test
    void preservesInsertionOrderSoFieldSourcesAreDeterministic() {
        assertThat(FieldMergePolicy.apply(incoming(), Set.of()).keySet())
                .containsExactly(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING);
    }

    @Test
    void emptyResultIsAllowedAndIsNotAnError() {
        assertThat(FieldMergePolicy.apply(incoming(),
                List.of(MetadataFields.TITLE, MetadataFields.SUMMARY, MetadataFields.RATING)))
                .isEmpty();
    }

    @Test
    void doesNotMutateTheInputMap() {
        Map<String, String> input = incoming();
        FieldMergePolicy.apply(input, List.of(MetadataFields.TITLE));

        assertThat(input).containsKey(MetadataFields.TITLE);
    }
}
