package com.mymedia.library;

import com.mymedia.shared.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 带密码的分享链接解锁后签发的短期通行证。
 *
 * <p>形状：{@code <过期时刻的 epoch 秒>.<base64url(HMAC-SHA256)>}，
 * 签的是 {@code token + ":" + 过期时刻}。<b>服务端不存任何东西</b>——
 * 校验就是重算一遍再比。签名原语在 {@link HmacSigner}。
 *
 * <p>为什么不是会话：一本漫画翻 20 页就是 20 次请求，每次重验 bcrypt 约 100 ms，
 * 阅读器会卡死；而给未登录访客建服务端状态又和「无状态 REST + 令牌即凭证」打架。
 */
@Component
class ShareTicket {

    private static final Logger log = LoggerFactory.getLogger(ShareTicket.class);

    private final HmacSigner signer;
    private final Duration ttl;

    ShareTicket(@Value("${mymedia.share.secret:}") String configuredSecret,
                @Value("${mymedia.share.ticket-ttl:PT12H}") Duration ttl) {
        this.ttl = ttl;
        this.signer = HmacSigner.of(configuredSecret);
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("未配置 mymedia.share.secret，本次启动使用随机密钥："
                    + "重启后带密码的分享链接需要访客重新输入一次密码");
        }
    }

    /**
     * 签发一张票据。
     *
     * @param linkExpiresAt 链接自身的过期时刻，可为 null（永不过期）。
     *                      <b>票据绝不能比链接活得久</b>，所以取二者较早的那个。
     */
    String issue(String token, Instant now, Instant linkExpiresAt) {
        Instant expiry = now.plus(ttl);
        if (linkExpiresAt != null && linkExpiresAt.isBefore(expiry)) {
            expiry = linkExpiresAt;
        }
        long epochSecond = expiry.getEpochSecond();
        return epochSecond + "." + signer.sign(payload(token, epochSecond));
    }

    /** 任何形状不对、过期、签名不符的输入一律 false，绝不抛异常。 */
    boolean verify(String token, String ticket, Instant now) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            return false;
        }
        long epochSecond;
        try {
            epochSecond = Long.parseLong(ticket.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.ofEpochSecond(epochSecond).isBefore(now)) {
            return false;
        }
        return signer.matches(payload(token, epochSecond), ticket.substring(dot + 1));
    }

    private static String payload(String token, long epochSecond) {
        return token + ":" + epochSecond;
    }
}
