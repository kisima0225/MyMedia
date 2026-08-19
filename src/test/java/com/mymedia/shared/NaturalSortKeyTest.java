package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalSortKeyTest {

    private List<String> sortNaturally(List<String> input) {
        return input.stream()
                .sorted(Comparator.comparing(NaturalSortKey::of))
                .toList();
    }

    @Test
    void sortsNumbersNumericallyNotLexically() {
        List<String> sorted = sortNaturally(List.of("第10卷", "第2卷", "第1卷"));

        assertThat(sorted).containsExactly("第1卷", "第2卷", "第10卷");
    }

    @Test
    void handlesEpisodeNumbering() {
        List<String> sorted = sortNaturally(List.of("E11.mkv", "E2.mkv", "E1.mkv", "E20.mkv"));

        assertThat(sorted).containsExactly("E1.mkv", "E2.mkv", "E11.mkv", "E20.mkv");
    }

    @Test
    void handlesMultipleNumberGroups() {
        List<String> sorted = sortNaturally(List.of("S1E10", "S1E2", "S10E1", "S2E1"));

        assertThat(sorted).containsExactly("S1E2", "S1E10", "S2E1", "S10E1");
    }

    @Test
    void treatsZeroPaddedNumbersAsEqualValue() {
        // 001 与 1 应排在一起，不因补零而分开
        assertThat(NaturalSortKey.of("ep001")).isEqualTo(NaturalSortKey.of("ep1"));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(NaturalSortKey.of("Movie")).isEqualTo(NaturalSortKey.of("movie"));
    }

    @Test
    void handlesVeryLargeNumbersWithoutOverflow() {
        List<String> sorted = sortNaturally(List.of(
                "x99999999999999999999", "x2", "x100"));

        assertThat(sorted).containsExactly("x2", "x100", "x99999999999999999999");
    }

    @Test
    void handlesPureText() {
        List<String> sorted = sortNaturally(List.of("banana", "apple", "cherry"));

        assertThat(sorted).containsExactly("apple", "banana", "cherry");
    }

    @Test
    void handlesEmptyAndNumberOnly() {
        assertThat(NaturalSortKey.of("")).isNotNull();
        assertThat(sortNaturally(List.of("10", "2", "1"))).containsExactly("1", "2", "10");
    }

    @Test
    void handlesChineseText() {
        List<String> sorted = sortNaturally(List.of("进击的巨人 第10话", "进击的巨人 第2话"));

        assertThat(sorted).containsExactly("进击的巨人 第2话", "进击的巨人 第10话");
    }
}
