package com.mymedia.image.event;

/**
 * 新建了一个图片节点。
 *
 * <p>由 {@code metadata} 模块订阅去刮削、{@code preview} 模块订阅去生成封面。
 * {@code image} 模块不知道它们的存在。
 */
public record ImageNodeCreated(Long nodeId, Long libraryId, String name) {
}