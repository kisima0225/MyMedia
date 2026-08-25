package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 分享链接的创建、撤销与令牌解析。
 *
 * <p>住在 {@code library} 而不是某个领域模块：一条链接指向哪个域是它的<b>数据</b>，
 * 不是它的<b>行为</b>。本类从不 import 任何 {@code video} / {@code image} 类型，
 * 因此 {@code library → shared, user} 的依赖表不需要任何改动。
 *
 * <p><b>创建时不校验目标是否存在</b>：那需要领域知识。校验由调用方
 * （{@code VideoShareLinkController} / {@code ImageShareLinkController}）在
 * 自己的模块里完成，数据库的外键是最后一道防线——目标 id 不存在时
 * INSERT 会直接违反外键。
 */
@Service
public class ShareLinkService {

    /** 32 字节 → Base64URL 无填充 43 字符，熵远高于 UUID 的 122 位。 */
    private static final int TOKEN_BYTES = 32;

    private final ShareLinkRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    ShareLinkService(ShareLinkRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ShareLink createForVideoItem(Long creatorId, Long libraryId, Long videoItemId,
                                        ShareLinkDto.CreateRequest request) {
        return create(creatorId, libraryId, videoItemId, null, request);
    }

    @Transactional
    public ShareLink createForImageNode(Long creatorId, Long libraryId, Long imageNodeId,
                                        ShareLinkDto.CreateRequest request) {
        return create(creatorId, libraryId, null, imageNodeId, request);
    }

    private ShareLink create(Long creatorId, Long libraryId, Long videoItemId, Long imageNodeId,
                             ShareLinkDto.CreateRequest request) {
        String hash = (request.password() == null || request.password().isBlank())
                ? null
                : passwordEncoder.encode(request.password());
        Instant expiresAt = request.expiresInDays() == null
                ? null
                : Instant.now().plus(Duration.ofDays(request.expiresInDays()));

        return repository.save(new ShareLink(newToken(), libraryId, videoItemId, imageNodeId,
                hash, expiresAt, creatorId));
    }

    @Transactional(readOnly = true)
    public List<ShareLink> listCreatedBy(Long creatorId) {
        return repository.findByCreatedByOrderByCreatedAtDesc(creatorId);
    }

    /**
     * 撤销。
     *
     * <p>撤销别人的链接返回 404 而不是 403：403 会确认「这个 id 确实存在」。
     * 与项目其余部分同一条纪律。
     */
    @Transactional
    public void revoke(Long creatorId, Long shareLinkId) {
        ShareLink link = repository.findById(shareLinkId)
                .filter(candidate -> candidate.getCreatedBy().equals(creatorId))
                .orElseThrow(() -> new NotFoundException("找不到分享链接 id=" + shareLinkId));
        link.revoke(Instant.now());
    }

    /**
     * 把令牌解析成一份临时只读授权。
     *
     * <p><b>无效、过期、已撤销一律抛同一个 {@link NotFoundException}</b>：
     * 区分它们等于告诉扫链接的人「这个令牌曾经存在」。
     * 密码是否正确不在这里判——那要等客户端拿票据来（Task 9）。
     */
    @Transactional(readOnly = true)
    public ShareGrant resolve(String token) {
        ShareLink link = repository.findByToken(token)
                .filter(candidate -> candidate.isUsableAt(Instant.now()))
                .orElseThrow(() -> new NotFoundException("分享链接不存在或已失效"));

        return new ShareGrant(link.getId(), link.getLibraryId(),
                link.getVideoItemId(), link.getImageNodeId(),
                link.isPasswordProtected(), link.getExpiresAt());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
