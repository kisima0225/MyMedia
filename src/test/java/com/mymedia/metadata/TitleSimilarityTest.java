package com.mymedia.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSimilarityTest {

    @Test
    void identicalTitlesScoreOne() {
        assertThat(TitleSimilarity.between("进击的巨人", "进击的巨人")).isEqualTo(1.0);
    }

    @Test
    void unrelatedChineseTitlesScoreZero() {
        assertThat(TitleSimilarity.between("进击的巨人", "夏目友人帐")).isZero();
    }

    @Test
    void aSubtitledSequelStillScoresHigh() {
        // 这正是需要人工确认的区间：像但不是同一个
        double score = TitleSimilarity.between("进击的巨人", "进击的巨人 最终季");
        assertThat(score).isBetween(0.4, 0.95);
    }

    @Test
    void substringRelationshipIsRecognisedUnlikeEditDistance() {
        // "巨人" 是 "进击的巨人" 的子串，编辑距离会给出很差的评价
        assertThat(TitleSimilarity.between("进击的巨人", "巨人")).isGreaterThan(0.3);
    }

    @Test
    void latinTitlesAreCompiledCaseInsensitively() {
        assertThat(TitleSimilarity.between("Big Buck Bunny", "big buck bunny")).isEqualTo(1.0);
    }

    @Test
    void whitespaceAndPunctuationDoNotAffectTheScore() {
        assertThat(TitleSimilarity.between("进击的巨人", "进击的·巨人 ")).isEqualTo(1.0);
    }

    @Test
    void emptyOrNullInputScoresZeroInsteadOfCrashing() {
        assertThat(TitleSimilarity.between("", "进击的巨人")).isZero();
        assertThat(TitleSimilarity.between(null, "进击的巨人")).isZero();
        assertThat(TitleSimilarity.between("进击的巨人", null)).isZero();
    }

    @Test
    void singleCharacterTitlesCompareByEquality() {
        // 一个字切不出二元组，退化成相等比较
        assertThat(TitleSimilarity.between("春", "春")).isEqualTo(1.0);
        assertThat(TitleSimilarity.between("春", "夏")).isZero();
    }

    @Test
    void repeatedCharactersAreCountedAsAMultisetNotASet() {
        // "AAAA" 与 "AA" 若按集合算会是 1.0，按多重集算应当低于 1
        assertThat(TitleSimilarity.between("AAAA", "AA")).isLessThan(1.0);
    }
}
