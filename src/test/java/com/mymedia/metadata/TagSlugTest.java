package com.mymedia.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagSlugTest {

    @Test
    void lowercasesAndHyphenatesLatin() {
        assertThat(TagSlug.of("Sci Fi")).isEqualTo("sci-fi");
    }

    @Test
    void keepsChineseCharactersAsTheyAre() {
        // 音译需要词表或外部库，而 slug 在这里只用来做唯一键
        assertThat(TagSlug.of("科幻")).isEqualTo("科幻");
    }

    @Test
    void collapsesRepeatedWhitespaceAndTrims() {
        assertThat(TagSlug.of("  科幻   动作  ")).isEqualTo("科幻-动作");
    }

    @Test
    void dropsPunctuationSoNearlyIdenticalNamesCollide() {
        // 「科幻！」与「科幻」应当是同一个标签
        assertThat(TagSlug.of("科幻！")).isEqualTo("科幻");
        assertThat(TagSlug.of("Sci-Fi!")).isEqualTo("sci-fi");
    }

    @Test
    void keepsExistingHyphensWithoutDoublingThem() {
        assertThat(TagSlug.of("sci - fi")).isEqualTo("sci-fi");
        assertThat(TagSlug.of("sci--fi")).isEqualTo("sci-fi");
    }

    @Test
    void trimsLeadingAndTrailingHyphens() {
        assertThat(TagSlug.of("-科幻-")).isEqualTo("科幻");
    }

    @Test
    void keepsDigits() {
        assertThat(TagSlug.of("2024 年度")).isEqualTo("2024-年度");
    }

    @Test
    void rejectsNamesThatSlugifyToNothing() {
        // 全是标点的名字没法做唯一键，必须当场拒绝而不是存一个空 slug
        assertThatThrownBy(() -> TagSlug.of("！！！"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagSlug.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
