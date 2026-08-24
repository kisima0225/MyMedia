package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;

import java.nio.file.Path;

/**
 * 一个待刮削的条目，已经把两个域的差异抹平成提供者需要的最小信息。
 *
 * @param domain      决定哪些提供者适用（TMDB 只管视频，Bangumi 两个域都能管）
 * @param targetId    {@code video_item.id} 或 {@code image_node.id}
 * @param libraryId   所属媒体库
 * @param title       当前标题（扫描时由文件名/目录名得出），搜索词就是它
 * @param year        从文件名里认出的年份，用于提高匹配置信度；认不出就是 {@code null}
 * @param primaryPath 该条目在磁盘上的代表位置——视频取 PRIMARY 文件，
 *                    图片取节点目录或压缩包本体。本地元数据文件就在它旁边找。
 *                    文件当前不可达时为 {@code null}。
 */
public record ScrapeSubject(
        LibraryDomain domain,
        Long targetId,
        Long libraryId,
        String title,
        Integer year,
        Path primaryPath) {
}
