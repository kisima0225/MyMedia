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
 * <p>分两步，因为 {@code scanned_file.content_hash} <b>绝大多数是 NULL</b>——
 * 计划 02 只在改名检测需要时才算它。只按哈希查会几乎全部落空，秒传形同虚设。
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
