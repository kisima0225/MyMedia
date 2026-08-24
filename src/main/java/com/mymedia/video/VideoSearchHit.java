package com.mymedia.video;

/**
 * 一条视频搜索结果。
 *
 * @param score 分层排序里的**首要分数**（子串命中时是三元组相似度，否则是 ts_rank）。
 *              只用于展示与调试；真正的顺序由 SQL 的 ORDER BY 决定，
 *              不要在 Java 侧拿它重排。
 */
public record VideoSearchHit(
        Long itemId,
        Long libraryId,
        String title,
        String sortTitle,
        Long coverAssetId,
        double score) {
}
