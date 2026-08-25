package com.mymedia.upload;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScanTrigger;
import com.mymedia.shared.SampledHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把到齐的分片合并成文件、校验、移进媒体库，再触发一次增量扫描。
 *
 * <p><b>本类没有一个方法是 {@code @Transactional} 的。</b>合并一个 20GB 的文件
 * 要读写 40GB，挂着事务会把连接一直占着。状态的每一次改动都是一次独立的短事务，
 * 由 {@code UploadSessionService} 的两个 package-private 方法完成。
 */
@Component
class UploadAssembler {

    private static final Logger log = LoggerFactory.getLogger(UploadAssembler.class);

    /** 重名后缀最多试到 (999)；再撞就是有人在刷，不是巧合。 */
    private static final int MAX_NAME_ATTEMPTS = 999;

    private final UploadSessionService sessionService;
    private final UploadChunkStore chunkStore;
    private final UploadStorage storage;
    private final LibraryService libraryService;
    private final ScanTrigger scanTrigger;

    UploadAssembler(UploadSessionService sessionService,
                    UploadChunkStore chunkStore,
                    UploadStorage storage,
                    LibraryService libraryService,
                    ScanTrigger scanTrigger) {
        this.sessionService = sessionService;
        this.chunkStore = chunkStore;
        this.storage = storage;
        this.libraryService = libraryService;
        this.scanTrigger = scanTrigger;
    }

    /**
     * @throws IOException 读写层面的失败。<b>抛出去</b>让任务表按指数退避重试——
     *         磁盘满、目标目录暂时不可写这类问题下一次可能就好了。
     *         而「哈希不符」这种再试一百遍也一样的失败，在方法内部标 FAILED 并正常返回。
     */
    void assemble(Long sessionId) throws IOException {
        UploadSession session = sessionService.forAssembly(sessionId);

        if (session.getStatus() != UploadStatus.ASSEMBLING) {
            log.info("会话 {} 当前状态是 {}，跳过合并", sessionId, session.getStatus());
            return;
        }
        int received = chunkStore.count(sessionId);
        if (received != session.getTotalChunks()) {
            sessionService.markFailed(sessionId,
                    "分片不齐：收到 " + received + " / " + session.getTotalChunks());
            return;
        }

        Path merged = storage.sessionDir(sessionId).resolve("assembled.bin");
        Files.deleteIfExists(merged);   // 上一次重试留下的残骸

        long total = storage.assembleInto(sessionId, session.getTotalChunks(), merged);
        if (total != session.getTotalSize()) {
            sessionService.markFailed(sessionId,
                    "合并后大小不符：期望 " + session.getTotalSize() + " 字节，实际 " + total);
            storage.deleteSession(sessionId);
            return;
        }

        String actual = SampledHash.of(merged, total);
        if (!actual.equals(session.getContentHash())) {
            // 采样哈希能抓住「传了另一个文件」和「丢了整块首尾」，
            // 抓不住中段几个字节的改动——边界写在 ADR-007
            sessionService.markFailed(sessionId,
                    "内容哈希不符：声明 " + session.getContentHash() + "，实际 " + actual);
            storage.deleteSession(sessionId);
            return;
        }

        Path libraryRoot = Path.of(
                libraryService.getById(session.getTargetLibraryId()).getRootPath());
        String relativePath = availableName(libraryRoot, session.getFilename());
        moveInto(merged, libraryRoot.resolve(relativePath));
        storage.deleteSession(sessionId);

        sessionService.markCompleted(sessionId, relativePath);
        log.info("上传合并完成 session={} -> {}", sessionId, relativePath);

        // 语义层由扫描链路照常建立：上传不认识「电影」也不认识「漫画」，
        // 它只负责把字节放到正确的目录里
        scanTrigger.requestScan(session.getTargetLibraryId());
    }

    /**
     * 目标目录里已经有同名文件时追加 {@code (2)}、{@code (3)}……
     *
     * <p><b>绝不覆盖</b>：那是别人的文件，而扫描是靠路径认文件的——
     * 覆盖等于悄悄换掉一部片子的内容，而目录树纹丝不动。
     */
    private static String availableName(Path libraryRoot, String filename) throws IOException {
        if (!Files.exists(libraryRoot.resolve(filename))) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";

        for (int n = 2; n <= MAX_NAME_ATTEMPTS; n++) {
            String candidate = stem + " (" + n + ")" + extension;
            if (!Files.exists(libraryRoot.resolve(candidate))) {
                return candidate;
            }
        }
        throw new IOException("目标目录里同名文件过多: " + filename);
    }

    /**
     * 临时目录与媒体库很可能不在同一个挂载点上，那时 {@code ATOMIC_MOVE} 会抛异常。
     * 先试原子改名（同盘时几乎瞬时），不行再退回复制。
     */
    private static void moveInto(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.copy(source, target);
            Files.delete(source);
        }
    }
}
