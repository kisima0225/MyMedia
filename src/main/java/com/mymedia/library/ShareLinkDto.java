package com.mymedia.library;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 分享链接的对外形状。
 *
 * <p><b>是 public 的</b>（`LibraryDto` 是 package-private）：创建端点住在
 * {@code video.web} 与 {@code image.web}，它们要绑定同一份请求体、返回同一份响应体。
 */
public final class ShareLinkDto {

    private ShareLinkDto() {
    }

    /**
     * @param password       为空表示不设密码
     * @param expiresInDays  为空表示永不过期。用「几天后」而不是绝对时刻，
     *                       是为了不必和客户端争论时钟与时区
     */
    public record CreateRequest(
            @Size(max = 128) String password,
            @Min(1) @Max(365) Integer expiresInDays) {
    }

    /**
     * 响应里<b>不含条目标题与封面</b>：带上它们，{@code library} 就得同时认识
     * {@code video_item} 与 {@code image_node} 两个模型。前端按 {@code domain}
     * 各自去取一次，代价小得多。
     */
    public record Response(
            Long id,
            String token,
            LibraryDomain domain,
            Long libraryId,
            Long targetId,
            boolean passwordProtected,
            Instant expiresAt,
            Instant createdAt,
            Instant revokedAt) {

        public static Response from(ShareLink link) {
            boolean video = link.getVideoItemId() != null;
            return new Response(
                    link.getId(),
                    link.getToken(),
                    video ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                    link.getLibraryId(),
                    video ? link.getVideoItemId() : link.getImageNodeId(),
                    link.isPasswordProtected(),
                    link.getExpiresAt(),
                    link.getCreatedAt(),
                    link.getRevokedAt());
        }
    }
}
