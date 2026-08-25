package com.mymedia.upload;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 上传会话的创建与查询。
 *
 * <p><b>分片大小由服务端决定并下发</b>，客户端不许自选：分片边界一旦由两边
 * 各自计算，「第 N 片」就没有共同含义了，断点续传会把文件拼错。
 */
@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    private final UploadSessionRepository repository;
    private final InstantUploadResolver instantResolver;
    private final LibraryAccessService accessService;
    private final UploadProperties properties;

    UploadSessionService(UploadSessionRepository repository,
                         InstantUploadResolver instantResolver,
                         LibraryAccessService accessService,
                         UploadProperties properties) {
        this.repository = repository;
        this.instantResolver = instantResolver;
        this.accessService = accessService;
        this.properties = properties;
    }

    /**
     * 建一个会话，顺带先试一次秒传。
     *
     * @param contentHash 客户端算好的采样哈希。<b>是必填的</b>——没有它就既没法秒传，
     *                    也没法在合并后校验拼出来的东西对不对。算它只需要读文件首尾
     *                    各 1MB，浏览器用 {@code File.slice} + WebCrypto 就能做到。
     */
    @Transactional
    public UploadSession create(Long userId, String filename, long totalSize,
                                String contentHash, Long libraryId) {
        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }
        if (totalSize <= 0 || totalSize > properties.maxSize()) {
            throw new IllegalArgumentException(
                    "文件大小超出允许范围（上限 " + properties.maxSize() + " 字节）: " + totalSize);
        }

        String safeName = SafeFileName.of(filename);
        int chunkSize = properties.chunkSize();
        // 向上取整；totalSize 已保证 > 0，所以至少一片
        int totalChunks = (int) ((totalSize + chunkSize - 1) / chunkSize);

        UploadSession session = new UploadSession(userId, libraryId, safeName, totalSize,
                chunkSize, totalChunks, contentHash);

        Optional<ScannedFile> existing = instantResolver.resolve(libraryId, totalSize, contentHash);
        if (existing.isPresent()) {
            // 已经有一份一模一样的了。再存一份物理副本不是「更安全」，只是浪费磁盘——
            // 这正是内容寻址的意义
            session.completeInstantly(existing.get().getId());
            log.info("秒传完成 user={} library={} file={} -> 既有文件 id={}",
                    userId, libraryId, safeName, existing.get().getId());
        }
        return repository.save(session);
    }

    /** 查会话。<b>别人的会话一律 404</b>，不确认它是否存在。 */
    @Transactional(readOnly = true)
    public UploadSession get(Long userId, Long sessionId) {
        return repository.findById(sessionId)
                .filter(session -> session.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("找不到上传会话 id=" + sessionId));
    }
}
