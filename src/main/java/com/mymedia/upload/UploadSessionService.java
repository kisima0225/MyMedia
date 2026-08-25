package com.mymedia.upload;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
    private final UploadStorage storage;
    private final UploadChunkStore chunkStore;

    UploadSessionService(UploadSessionRepository repository,
                         InstantUploadResolver instantResolver,
                         LibraryAccessService accessService,
                         UploadProperties properties,
                         UploadStorage storage,
                         UploadChunkStore chunkStore) {
        this.repository = repository;
        this.instantResolver = instantResolver;
        this.accessService = accessService;
        this.properties = properties;
        this.storage = storage;
        this.chunkStore = chunkStore;
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

    /**
     * 收下一个分片。
     *
     * <p><b>本方法刻意不是 {@code @Transactional} 的。</b>一个 8MB 分片在慢网络上
     * 可能写好几秒，若整个方法挂着事务，连接池会被一群正在传输的请求占满，
     * 整个应用的其它部分全部卡住。读会话是一次短事务，落盘不在事务里，
     * 记录分片又是一次短事务——事务边界贴着数据库操作，不贴着业务动作。
     *
     * @throws IllegalArgumentException 下标越界，或分片大小与声明不符（→ 400）
     * @throws ResponseStatusException  会话已经不在收片状态（→ 409）。用它是因为
     *         「状态不对」没有合适的领域异常，而 {@code shared} 的
     *         {@code GlobalExceptionHandler} 不可能认识每个模块自己的异常类型
     */
    public void receiveChunk(Long userId, Long sessionId, int index, InputStream body)
            throws IOException {

        UploadSession session = get(userId, sessionId);

        if (session.getStatus() != UploadStatus.RECEIVING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "会话当前状态不接受分片: " + session.getStatus());
        }
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new IllegalArgumentException("分片下标越界: " + index
                    + "，本次上传共 " + session.getTotalChunks() + " 片");
        }

        long expected = expectedChunkSize(session, index);
        long written = storage.writeChunk(sessionId, index, body, expected);
        if (written != expected) {
            throw new IllegalArgumentException(
                    "分片大小不符：期望 " + expected + " 字节，实际 " + written);
        }
        chunkStore.record(sessionId, index, written);
    }

    /** 最后一片通常是短的；其余都是整片。 */
    private static long expectedChunkSize(UploadSession session, int index) {
        long offset = (long) index * session.getChunkSize();
        return Math.min(session.getChunkSize(), session.getTotalSize() - offset);
    }

    /**
     * 已经收到的分片下标，升序。
     *
     * <p><b>断点续传的全部机制就是这一个列表</b>：客户端看一眼就知道要补哪几片。
     * 不需要服务端记住「传到第几个字节」——分片是原子的，要么整片到了要么没到。
     */
    @Transactional(readOnly = true)
    public List<Integer> receivedChunks(Long userId, Long sessionId) {
        return chunkStore.receivedIndexes(get(userId, sessionId).getId());
    }
}
