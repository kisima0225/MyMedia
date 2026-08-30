package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SampledHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 内容指纹的查询与按需补算。
 *
 * <p>为什么不并进 {@code ScannedFileQueryService}：那个类明写「不能修改物理层状态」，
 * 而本类要往 {@code scanned_file.content_hash} 里写。物理层状态的所有权仍在
 * {@code scan} 手里，对外只暴露「给我算一下并存起来」这一个动作。
 *
 * <p>调用方是 {@code upload} 的秒传判定。注意 {@code content_hash} <b>并不是
 * 「绝大多数为 NULL」</b>：{@code LibraryScanner.scan} 在每次对账之后会对本库每个
 * ACTIVE 文件调一次 {@code ensureHash}，缺失就补，所以扫过一遍的库里按哈希查通常
 * 命中。「取同尺寸候选现算」这条兜底路径仍然需要，但它兜的是**哈希计算抛过异常的
 * 文件**与**上次扫描之后才出现的文件**，而不是大多数文件。
 */
@Service
public class ScannedFileHashService {

    private static final Logger log = LoggerFactory.getLogger(ScannedFileHashService.class);

    private final ScannedFileRepository repository;
    private final LibraryService libraryService;

    ScannedFileHashService(ScannedFileRepository repository, LibraryService libraryService) {
        this.repository = repository;
        this.libraryService = libraryService;
    }

    @Transactional(readOnly = true)
    public Optional<ScannedFile> findActiveByContentHash(Long libraryId, String hash) {
        return repository.findByLibraryIdAndContentHashAndStatus(
                libraryId, hash, ScannedFileStatus.ACTIVE);
    }

    /** 同库、同大小、还没算过哈希的 ACTIVE 文件，按 id 升序，最多 {@code limit} 个。 */
    @Transactional(readOnly = true)
    public List<ScannedFile> findActiveBySizeWithoutHash(Long libraryId, long sizeBytes, int limit) {
        return repository.findByLibraryIdAndSizeBytesAndContentHashIsNullAndStatusOrderById(
                libraryId, sizeBytes, ScannedFileStatus.ACTIVE, Limit.of(limit));
    }

    /**
     * 现算一个文件的指纹并写回。
     *
     * <p>读不到文件（外接盘没挂、权限不对）时返回空而不是抛异常：
     * 秒传是<b>优化</b>，读不到就当没命中，正常走分片上传。
     */
    @Transactional
    public Optional<String> computeAndStoreHash(Long scannedFileId) {
        ScannedFile file = repository.findById(scannedFileId)
                .orElseThrow(() -> new NotFoundException("找不到扫描文件 id=" + scannedFileId));
        if (file.getContentHash() != null) {
            return Optional.of(file.getContentHash());
        }
        Path root = Path.of(libraryService.getById(file.getLibraryId()).getRootPath());
        try {
            String hash = SampledHash.of(root.resolve(file.getRelativePath()), file.getSizeBytes());
            file.assignContentHash(hash);
            return Optional.of(hash);
        } catch (IOException e) {
            log.warn("补算内容哈希失败，跳过: {}", file.getRelativePath(), e);
            return Optional.empty();
        }
    }
}
