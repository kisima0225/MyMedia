package com.mymedia.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    @Test
    void 同一密钥同一载荷签出同一结果() {
        HmacSigner a = HmacSigner.of("secret-key");
        HmacSigner b = HmacSigner.of("secret-key");
        assertThat(a.sign("payload")).isEqualTo(b.sign("payload"));
    }

    @Test
    void 不同密钥签出不同结果() {
        assertThat(HmacSigner.of("key-one").sign("payload"))
                .isNotEqualTo(HmacSigner.of("key-two").sign("payload"));
    }

    @Test
    void 签名是无填充的_base64url() {
        String signature = HmacSigner.of("secret-key").sign("payload");
        assertThat(signature).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void matches_对正确签名返回真() {
        HmacSigner signer = HmacSigner.of("secret-key");
        assertThat(signer.matches("payload", signer.sign("payload"))).isTrue();
    }

    @Test
    void matches_对错误签名与_null_返回假而不抛异常() {
        HmacSigner signer = HmacSigner.of("secret-key");
        assertThat(signer.matches("payload", "not-a-signature")).isFalse();
        assertThat(signer.matches("payload", null)).isFalse();
        assertThat(signer.matches("payload", "")).isFalse();
    }

    @Test
    void 空密钥走随机生成_两个实例互不认账() {
        assertThat(HmacSigner.of("").sign("payload"))
                .isNotEqualTo(HmacSigner.of("").sign("payload"));
        assertThat(HmacSigner.of(null).sign("payload"))
                .isNotEqualTo(HmacSigner.of(null).sign("payload"));
    }

    @Test
    void randomSecret_每次不同且足够长() {
        assertThat(HmacSigner.randomSecret())
                .isNotEqualTo(HmacSigner.randomSecret())
                .hasSizeGreaterThanOrEqualTo(32);
    }
}
