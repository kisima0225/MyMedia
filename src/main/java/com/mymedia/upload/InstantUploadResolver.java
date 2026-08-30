package com.mymedia.upload;

import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 秒传判定：这个库里是不是已经有一份一模一样的文件了。
 *
 * <p>分两步。第 1 步按 {@code content_hash} 直接查：扫描已经为每个 ACTIVE 文件
 * 补齐了哈希（{@code LibraryScanner.scan} → {@code ensureHash}），所以这一步通常
 * 就能命中。第 2 步是兜底，覆盖第 1 步查不到的两类文件——<b>哈希计算抛过异常的</b>
 * 与<b>上次扫描之后才出现的</b>。
 *
 * <ol>
 *   <li>按 {@code content_hash} 直接查（部分索引，一次查找）</li>
 *   <li>未命中时取同库内 {@code size_bytes} 相同且哈希为空的候选，
 *       <b>现算并写回</b>，再比</li>
 * </ol>
 *
 * <p>第 2 步顺带把哈希补齐了，下次更快。代价是最多 {@value #MAX_CANDIDATES} 次
 * 2MB 读——上限是必须的：一个库里可能有几百个大小恰好相同的文件
 * （同一台设备导出的视频尤其容易撞），不设限就会让创建会话这一个请求读上几 GB。
 *
 * <p>取舍与边界写在 ADR-007。
 */
@Component
class InstantUploadResolver {

    /** 同尺寸候选的现算上限。 */
    static final int MAX_CANDIDATES = 8;

    private static final Logger log = LoggerFactory.getLogger(InstantUploadResolver.class);

    private final ScannedFileHashService hashService;

    InstantUploadResolver(ScannedFileHashService hashService) {
        this.hashService = hashService;
    }

    Optional<ScannedFile> resolve(Long libraryId, long sizeBytes, String contentHash) {
        Optional<ScannedFile> direct = hashService.findActiveByContentHash(libraryId, contentHash);
        if (direct.isPresent()) {
            log.debug("秒传命中（已有哈希）: libraryId={} hash={}", libraryId, contentHash);
            return direct;
        }

        for (ScannedFile candidate :
                hashService.findActiveBySizeWithoutHash(libraryId, sizeBytes, MAX_CANDIDATES)) {

            Optional<String> computed = hashService.computeAndStoreHash(candidate.getId());
            if (computed.filter(contentHash::equals).isPresent()) {
                log.debug("秒传命中（现算候选）: libraryId={} path={}",
                        libraryId, candidate.getRelativePath());
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
