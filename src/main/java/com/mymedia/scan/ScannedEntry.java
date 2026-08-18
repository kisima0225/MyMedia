package com.mymedia.scan;

import com.mymedia.scan.spi.MediaKind;

import java.time.Instant;

/**
 * 一次遍历中发现的磁盘文件快照，尚未与数据库比对。
 *
 * @param relativePath 相对于媒体库根路径，统一用正斜杠以保证跨平台可比
 */
record ScannedEntry(
        String relativePath,
        long sizeBytes,
        Instant mtime,
        String extension,
        MediaKind kind) {
}
