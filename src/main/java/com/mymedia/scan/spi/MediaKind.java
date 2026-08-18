package com.mymedia.scan.spi;

/**
 * 物理文件的粗粒度分类，由扩展名判定。
 *
 * <p>这是 {@code scan} 模块对领域模块的唯一分类承诺——它不知道
 * 一个 mkv 是电影还是番剧，那是 {@code video} 模块的职责。
 */
public enum MediaKind {
    VIDEO,
    IMAGE,
    AUDIO,
    /** 压缩包形态的图片集合，如 CBZ / ZIP。 */
    ARCHIVE,
    /** 非媒体文件：字幕、NFO、系统文件等。扫描时直接跳过。 */
    IGNORED
}
