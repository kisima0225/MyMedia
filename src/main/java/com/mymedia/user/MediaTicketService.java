package com.mymedia.user;

import com.mymedia.shared.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 登录用户的短期媒体票据。
 *
 * <p><b>为什么需要它</b>：本服务用 HTTP Basic 认证（ADR-002），而浏览器的
 * {@code <video src>} 与 {@code <img src>} <b>无法携带 Authorization 头</b>。
 * 用 fetch 取回再转 blob URL 对图片勉强可行，对视频则是灾难——整个文件读进内存，
 * 而且 Range 与拖动定位全部作废，计划 03 最有技术含量的那条链路白做。
 *
 * <p>形状：{@code <userId>.<过期时刻的 epoch 秒>.<base64url(HMAC-SHA256)>}，
 * 签的是 {@code userId + ":" + 过期时刻}。与 {@code library.ShareTicket} 一样，
 * <b>服务端不存任何东西</b>。
 *
 * <p><b>票据不是完整身份</b>：它只在
 * {@code /api/video/stream/**}、{@code /api/image/page/**}、{@code /api/assets/**}
 * 三条只读路径上被接受，白名单在 {@code MediaTicketAuthenticationFilter} 里。
 * URL 里的凭证会漏进服务器日志、Referer 头与浏览器历史，把它能干的事
 * 限制在「看内容」上，泄漏的代价就被钉死了。这条取舍见 ADR-008。
 *
 * <p><b>密钥默认随机</b>：TTL 只有 15 分钟且前端会自动续签，重启后最坏结果是
 * 前端多取一次票据。这与 {@code ShareTicket} 不同——那里密钥随机会让访客重输密码，
 * 是用户可感知的缺陷（总览 §5 G14）。同一个算法，两种生命周期，两种默认值。
 */
@Service
public class MediaTicketService {

    private final HmacSigner signer;
    private final Duration ttl;

    MediaTicketService(@Value("${mymedia.auth.media-ticket-secret:}") String configuredSecret,
                       @Value("${mymedia.auth.media-ticket-ttl:PT15M}") Duration ttl) {
        this.signer = HmacSigner.of(configuredSecret);
        this.ttl = ttl;
    }

    public Issued issue(Long userId, Instant now) {
        Instant expiresAt = now.plus(ttl);
        long epochSecond = expiresAt.getEpochSecond();
        String ticket = userId + "." + epochSecond + "." + signer.sign(payload(userId, epochSecond));
        return new Issued(ticket, Instant.ofEpochSecond(epochSecond));
    }

    /** 任何形状不对、过期、签名不符的输入一律返回空，绝不抛异常。 */
    public Optional<Long> resolve(String ticket, Instant now) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String[] parts = ticket.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long userId;
        long epochSecond;
        try {
            userId = Long.parseLong(parts[0]);
            epochSecond = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Instant.ofEpochSecond(epochSecond).isBefore(now)) {
            return Optional.empty();
        }
        if (!signer.matches(payload(userId, epochSecond), parts[2])) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    private static String payload(long userId, long epochSecond) {
        return userId + ":" + epochSecond;
    }

    /** @param expiresAt 前端据此在过期前主动续签，不要等到 401 */
    public record Issued(String ticket, Instant expiresAt) {
    }
}
