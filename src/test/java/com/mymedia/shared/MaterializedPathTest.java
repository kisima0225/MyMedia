package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterializedPathTest {

    @Test
    void rootPathIsSingleSlash() {
        assertThat(MaterializedPath.rootPath()).isEqualTo("/");
    }

    @Test
    void childAppendsParentIdAndTrailingSlash() {
        assertThat(MaterializedPath.childOf("/", 1L)).isEqualTo("/1/");
        assertThat(MaterializedPath.childOf("/1/", 17L)).isEqualTo("/1/17/");
        assertThat(MaterializedPath.childOf("/1/17/", 93L)).isEqualTo("/1/17/93/");
    }

    @Test
    void ancestorIdsAreParsedInOrder() {
        assertThat(MaterializedPath.ancestorIds("/1/17/93/")).containsExactly(1L, 17L, 93L);
        assertThat(MaterializedPath.ancestorIds("/")).isEmpty();
    }

    @Test
    void depthCountsSegments() {
        assertThat(MaterializedPath.depthOf("/")).isZero();
        assertThat(MaterializedPath.depthOf("/1/")).isEqualTo(1);
        assertThat(MaterializedPath.depthOf("/1/17/93/")).isEqualTo(3);
    }

    @Test
    void subtreePrefixMatchesDescendantsOnly() {
        String prefix = MaterializedPath.subtreePrefix("/1/17/");

        assertThat("/1/17/93/").startsWith(prefix);
        assertThat("/1/17/").startsWith(prefix);
        // 关键：/1/170/ 不是 /1/17/ 的子树，前缀必须以斜杠收尾才不会误匹配
        assertThat("/1/170/".startsWith(prefix)).isFalse();
    }

    @Test
    void rewriteReplacesPrefixForSubtreeMove() {
        // 把 /1/17/ 整棵子树移到 /5/ 下面
        String moved = MaterializedPath.rewrite("/1/17/93/", "/1/17/", "/5/17/");

        assertThat(moved).isEqualTo("/5/17/93/");
    }

    @Test
    void rewriteLeavesUnrelatedPathsUntouched() {
        assertThat(MaterializedPath.rewrite("/2/8/", "/1/17/", "/5/17/")).isEqualTo("/2/8/");
    }

    @Test
    void rejectsMalformedPath() {
        assertThatThrownBy(() -> MaterializedPath.ancestorIds("1/17"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializedPath.childOf("/1", 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesDeepPaths() {
        String path = MaterializedPath.rootPath();
        for (long i = 1; i <= 32; i++) {
            path = MaterializedPath.childOf(path, i);
        }

        assertThat(MaterializedPath.depthOf(path)).isEqualTo(32);
        assertThat(MaterializedPath.ancestorIds(path)).hasSize(32).endsWith(32L);
    }
}
