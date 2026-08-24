package com.mymedia.metadata;

/**
 * 一个提供者给出的候选匹配。
 *
 * @param score   置信度 0.0–1.0。高分自动应用，中分进待确认队列，
 *                <b>绝不在低置信度下强行写入</b>（spec 7.2 规则 4）。
 * @param payload 该候选的原始响应片段，原样存进 {@code scrape_candidate.payload}，
 *                用户确认时不必再查一次
 */
public record MetadataCandidate(
        String provider,
        String externalId,
        String title,
        Integer year,
        double score,
        String payload) {
}
