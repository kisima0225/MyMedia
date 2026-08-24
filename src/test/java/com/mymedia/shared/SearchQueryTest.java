package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        SearchQuery query = SearchQuery.of("  进击的   巨人 ");

        assertThat(query.normalized()).isEqualTo("进击的 巨人");
    }

    @Test
    void loweredIsUsedForSimilarityScoringSoLatinCaseDoesNotHurtRanking() {
        // similarity() 是大小写敏感的，Big 与 big 切出来的三元组不同
        assertThat(SearchQuery.of("Big Buck Bunny").lowered()).isEqualTo("big buck bunny");
    }

    @Test
    void wrapsPatternInWildcardsForSubstringMatching() {
        assertThat(SearchQuery.of("巨人").likePattern()).isEqualTo("%巨人%");
    }

    @Test
    void escapesPercentSoItIsNotTreatedAsAWildcard() {
        // 搜 "50%" 若不转义会变成"以 50 开头的任意串"
        assertThat(SearchQuery.of("50%").likePattern()).isEqualTo("%50\\%%");
    }

    @Test
    void escapesUnderscore() {
        assertThat(SearchQuery.of("a_b").likePattern()).isEqualTo("%a\\_b%");
    }

    @Test
    void escapesBackslashFirstSoEscapingIsNotDoubled() {
        // 反斜杠必须最先转义，否则后面转出来的反斜杠会被再转一次
        assertThat(SearchQuery.of("a\\b").likePattern()).isEqualTo("%a\\\\b%");
    }

    @Test
    void reportsWhetherTheTrigramIndexCanHelp() {
        // 实测：少于 3 个字符时 GIN trgm 索引提取不出三元组，退化成全表扫描
        assertThat(SearchQuery.of("进击的").usesTrigramIndex()).isTrue();
        assertThat(SearchQuery.of("巨人").usesTrigramIndex()).isFalse();
        assertThat(SearchQuery.of("巨").usesTrigramIndex()).isFalse();
    }

    @Test
    void countsCodePointsNotCharsSoAstralSymbolsAreNotMiscounted() {
        // 三个 emoji 是 6 个 char、3 个码点
        assertThat(SearchQuery.of("😀😀😀").usesTrigramIndex()).isTrue();
        assertThat(SearchQuery.of("😀😀").usesTrigramIndex()).isFalse();
    }

    @Test
    void rejectsBlankInputInsteadOfMatchingEverything() {
        // 空查询若放行，likePattern 会是 '%%'，等于把整个库倒出来
        assertThatThrownBy(() -> SearchQuery.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchQuery.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
