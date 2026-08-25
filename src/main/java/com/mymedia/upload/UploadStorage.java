package com.mymedia.upload;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * 未合并分片的临时存储。
 *
 * <p>布局 {@code {tempRoot}/{sessionId}/{index}.part}。<b>两级都是数字</b>——
 * 用户给的 {@code filename} 在收分片这一路根本不参与路径构造，
 * 少一个能出错的地方。它只在 Task 12 的合并阶段才被用到，那时已经过
 * {@code SafeFileName} 净化。
 *
 * <p>临时目录独立于媒体库根目录：<b>半成品绝不能出现在会被扫描的目录里</b>，
 * 否则扫描会把一个只传了一半的文件当成新媒体入库。
 */
@Component
class UploadStorage {

    private final Path tempRoot;

    UploadStorage(UploadProperties properties) {
        this.tempRoot = properties.tempRoot().toAbsolutePath().normalize();
    }

    Path sessionDir(Long sessionId) {
        return tempRoot.resolve(String.valueOf(sessionId));
    }

    Path chunkPath(Long sessionId, int index) {
        return sessionDir(sessionId).resolve(index + ".part");
    }

    /**
     * 把请求体落成一个分片，返回实际写入的字节数。
     *
     * <p><b>最多读 {@code limit + 1} 字节</b>：多出来的那一个字节是探针，
     * 能读到它就说明客户端超发了。不设上限的话，一个撒谎的 {@code Content-Length}
     * 就能把磁盘写满。调用方负责比对返回值与期望值。
     *
     * <p>先写 {@code .tmp} 再原子改名：中途断线不会留下一个"看起来完整"的分片。
     */
    long writeChunk(Long sessionId, int index, InputStream body, long limit) throws IOException {
        Path dir = sessionDir(sessionId);
        Files.createDirectories(dir);
        Path staging = dir.resolve(index + ".part.tmp");

        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(staging)) {
            while (written <= limit) {
                int read = body.read(buffer, 0, (int) Math.min(buffer.length, limit + 1 - written));
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                written += read;
            }
        }

        if (written != limit) {
            Files.deleteIfExists(staging);
            return written;
        }
        Files.move(staging, chunkPath(sessionId, index),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return written;
    }

    /** 合并完成或会话作废时清掉整个目录。 */
    void deleteSession(Long sessionId) throws IOException {
        Path dir = sessionDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
