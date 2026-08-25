package com.mymedia.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容指纹：文件长度 + 首尾各 1MB 的 SHA-256。
 *
 * <p>对大文件只读首尾各 {@value #SAMPLE_WINDOW} 字节，再把文件长度混入摘要。
 * 全量哈希一个 20GB 视频在机械盘上需要数分钟，扫描承受不起。
 *
 * <p><b>取舍</b>：两个首尾相同、仅中段不同的大文件会得到相同指纹。
 * 改名检测的场景是「同一个文件换了位置」，首尾加长度足以区分不同的媒体文件。
 *
 * <p><b>住在 {@code shared} 的理由</b>：改名检测（{@code scan}）与秒传
 * （{@code upload}）都要用它，而它是纯算法、不带任何模块的状态——
 * 与 {@code NaturalSortKey}、{@code MaterializedPath} 同类，
 * 符合本项目「复用算法，不复用模型」的惯例。
 *
 * <p><b>算法一个字节都不能改</b>：{@code scanned_file.content_hash} 里已经存了按它
 * 算出来的值，改了会让所有既有指纹失效，下一次扫描会把全部文件当成新文件。
 */
public final class SampledHash {

    /** 首尾各采样的字节数。 */
    private static final int SAMPLE_WINDOW = 1024 * 1024;

    private SampledHash() {
    }

    public static String of(Path file, long sizeBytes) throws IOException {
        MessageDigest digest = newDigest();

        // 长度必须参与摘要：否则前缀相同、长度不同的文件会碰撞
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(sizeBytes).array());

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (sizeBytes <= 2L * SAMPLE_WINDOW) {
                // 小文件直接全量读，中段差异也能分辨
                digestRegion(digest, channel, 0, sizeBytes);
            } else {
                digestRegion(digest, channel, 0, SAMPLE_WINDOW);
                digestRegion(digest, channel, sizeBytes - SAMPLE_WINDOW, SAMPLE_WINDOW);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void digestRegion(MessageDigest digest, FileChannel channel,
                                     long position, long length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = length;
        long offset = position;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer, offset);
            if (read <= 0) {
                break;
            }
            buffer.flip();
            digest.update(buffer);
            offset += read;
            remaining -= read;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
