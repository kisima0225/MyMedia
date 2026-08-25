package com.mymedia.library;

import java.time.Instant;

/**
 * 一个已校验通过的令牌所代表的临时只读授权。
 *
 * <p>它是 {@code library} 交给两个领域模块的<b>唯一</b>凭据：领域模块拿到它就知道
 * 「允许访问哪个库的哪一个目标」，而不需要知道令牌长什么样、有没有过期、
 * 密码对不对——那些在 {@link ShareLinkService#resolve} 里已经判完了。
 *
 * <p>两个目标字段恰有一个非空，与数据库上的 CHECK 约束一一对应。
 */
public record ShareGrant(
        Long shareLinkId,
        Long libraryId,
        Long videoItemId,
        Long imageNodeId,
        boolean passwordProtected,
        Instant expiresAt) {

    public boolean isVideo() {
        return videoItemId != null;
    }

    public boolean isImage() {
        return imageNodeId != null;
    }
}
