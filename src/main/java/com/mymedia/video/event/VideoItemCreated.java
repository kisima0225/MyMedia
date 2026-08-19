package com.mymedia.video.event;

/**
 * 新建了一个视频条目。
 *
 * <p>由 {@code metadata} 模块订阅去刮削、{@code preview} 模块订阅去生成封面。
 * {@code video} 模块不知道它们的存在。
 */
public record VideoItemCreated(Long itemId, Long libraryId, String title) {
}
