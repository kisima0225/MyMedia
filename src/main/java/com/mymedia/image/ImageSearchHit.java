package com.mymedia.image;

/**
 * 一条图片搜索结果。
 *
 * @param name     目录或压缩包的原名，一定有
 * @param title    刮削回来的标题，可能为 {@code null}；展示时优先它
 * @param readable {@code direct_page_count > 0}，界面据此决定点进去是阅读器还是子项网格
 * @param score    分层排序里的**首要分数**（子串命中时是三元组相似度，否则是 ts_rank）。
 *                 只用于展示与调试；真正的顺序由 SQL 的 ORDER BY 决定，不要在 Java 侧拿它重排。
 */
public record ImageSearchHit(
        Long nodeId,
        Long libraryId,
        String name,
        String title,
        Long coverAssetId,
        int totalPageCount,
        boolean readable,
        double score) {
}
