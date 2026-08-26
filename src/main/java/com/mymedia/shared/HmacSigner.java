package com.mymedia.shared;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * HMAC-SHA256 签名与定时安全比较。
 *
 * <p>本项目有两处「服务端不存任何东西、校验就是重算一遍再比」的票据机制：
 * {@code library.ShareTicket}（分享链接解锁后的通行证）与
 * {@code user.MediaTicketService}（登录用户的短期媒体票据）。
 * 算法完全相同，生命周期完全不同，因此复用算法、不复用票据类型——
 * 与 {@code NaturalSortKey} / {@code MaterializedPath} 放在本包里是同一个理由。
 *
 * <p>本类不可变且线程安全。
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int RANDOM_SECRET_BYTES = 32;

    private final byte[] secret;

    private HmacSigner(byte[] secret) {
        this.secret = secret;
    }

    /**
     * 按配置值建一个签名器。
     *
     * <p>{@code configuredSecret} 为空或全空白时**随机生成一把一次性密钥**：
     * 这样做的后果是「重启后旧票据一律失效」，是否可接受由调用方判断，
     * 本类不打日志也不做判断——它只管签名。
     */
    public static HmacSigner of(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[RANDOM_SECRET_BYTES];
            new SecureRandom().nextBytes(generated);
            return new HmacSigner(generated);
        }
        return new HmacSigner(configuredSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成一个可写进配置文件的随机密钥，供部署文档与测试使用。 */
    public static String randomSecret() {
        byte[] generated = new byte[RANDOM_SECRET_BYTES];
        new SecureRandom().nextBytes(generated);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
    }

    /** 签名，返回无填充的 base64url——它要能安全地出现在 URL 与 HTTP 头里。 */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 必须支持 " + ALGORITHM, e);
        }
    }

    /**
     * 校验签名。任何 null、空串、长度不符一律返回 false，绝不抛异常。
     *
     * <p>用 {@link MessageDigest#isEqual} 而非 {@code String.equals}：后者在第一个
     * 不同字节处就返回，把比较耗时变成一个可测量的旁路信道。
     */
    public boolean matches(String payload, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
