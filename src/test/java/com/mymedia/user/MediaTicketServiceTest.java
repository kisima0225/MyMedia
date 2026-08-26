package com.mymedia.user;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    private MediaTicketService service(String secret, Duration ttl) {
        return new MediaTicketService(secret, ttl);
    }

    @Test
    void 签发的票据能被同一个服务解析回用户_id() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));
        MediaTicketService.Issued issued = service.issue(42L, NOW);

        assertThat(service.resolve(issued.ticket(), NOW)).contains(42L);
    }

    @Test
    void 过期时刻等于签发时刻加_ttl() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));
        MediaTicketService.Issued issued = service.issue(42L, NOW);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void 过期后解析失败() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));
        MediaTicketService.Issued issued = service.issue(42L, NOW);

        assertThat(service.resolve(issued.ticket(), NOW.plus(Duration.ofMinutes(16)))).isEmpty();
    }

    @Test
    void 换一把密钥就认不出来() {
        MediaTicketService issuer = service("secret-one", Duration.ofMinutes(15));
        MediaTicketService verifier = service("secret-two", Duration.ofMinutes(15));

        assertThat(verifier.resolve(issuer.issue(42L, NOW).ticket(), NOW)).isEmpty();
    }

    @Test
    void 改掉票据里的用户_id_会让签名失配() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));
        String ticket = service.issue(42L, NOW).ticket();
        String tampered = "43" + ticket.substring(ticket.indexOf('.'));

        assertThat(service.resolve(tampered, NOW)).isEmpty();
    }

    @Test
    void 改掉票据里的过期时刻也会让签名失配() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));
        MediaTicketService.Issued issued = service.issue(42L, NOW);
        String[] parts = issued.ticket().split("\\.");
        String tampered = parts[0] + "." + (Long.parseLong(parts[1]) + 3600) + "." + parts[2];

        assertThat(service.resolve(tampered, NOW)).isEmpty();
    }

    @Test
    void 各种畸形输入一律返回空而不抛异常() {
        MediaTicketService service = service("fixed-secret", Duration.ofMinutes(15));

        for (String bad : new String[]{null, "", "   ", "no-dots", "42.only-two", "..",
                                       "abc.123.sig", "42.abc.sig", "42.123", ".123.sig"}) {
            assertThat(service.resolve(bad, NOW))
                    .as("输入 %s 应当安静地失败", bad)
                    .isEqualTo(Optional.empty());
        }
    }

    @Test
    void 密钥留空时每个实例各签各的() {
        assertThat(service("", Duration.ofMinutes(15))
                .resolve(service("", Duration.ofMinutes(15)).issue(42L, NOW).ticket(), NOW))
                .isEmpty();
    }
}
