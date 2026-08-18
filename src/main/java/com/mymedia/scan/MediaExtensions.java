package com.mymedia.scan;

import com.mymedia.scan.spi.MediaKind;

import java.util.Locale;
import java.util.Set;

/**
 * 基于扩展名的文件分类。
 *
 * <p>刻意不使用内容嗅探（Tika / {@code Files.probeContentType}）：媒体扫描
 * 需要确定性——同一个文件每次扫描必须得到相同判定；而且需要明确忽略
 * 字幕、NFO 等伴生文件，白名单天然表达这个意图。
 */
final class MediaExtensions {

    private static final Set<String> VIDEO = Set.of(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts");

    private static final Set<String> IMAGE = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "tiff", "tif");

    private static final Set<String> ARCHIVE = Set.of("cbz", "zip");

    private MediaExtensions() {
    }

    static MediaKind classify(String fileName) {
        String ext = extensionOf(fileName);
        if (VIDEO.contains(ext)) {
            return MediaKind.VIDEO;
        }
        if (IMAGE.contains(ext)) {
            return MediaKind.IMAGE;
        }
        if (ARCHIVE.contains(ext)) {
            return MediaKind.ARCHIVE;
        }
        return MediaKind.IGNORED;
    }

    /**
     * 提取小写扩展名，无扩展名时返回空串。
     *
     * <p>以点开头的文件名（如 {@code .DS_Store}）没有扩展名——那个点是
     * 隐藏文件标记，不是分隔符。
     */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
