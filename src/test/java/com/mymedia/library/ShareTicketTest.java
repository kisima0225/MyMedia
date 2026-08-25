package com.mymedia.library;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShareTicketTest {

    private final ShareTicket ticket = new ShareTicket("test-secret-do-not-use", Duration.ofHours(12));

    private static final String TOKEN = "HHiVX3lHTuKrH0P-Y8sJ0dnhkFYCkDBPa2b7pt2X0Kg";

    @Test
    void aFreshTicketVerifiesAgainstTheTokenItWasIssuedFor() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify(TOKEN, issued, now)).isTrue();
    }

    @Test
    void aTicketIssuedForOneTokenDoesNotWorkOnAnother() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify("Zm9vYmFyLXNvbWUtb3RoZXItc2hhcmUtdG9rZW4tMTIzNDU2", issued, now))
                .isFalse();
    }

    @Test
    void anExpiredTicketIsRejected() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        String issued = ticket.issue(TOKEN, now, null);

        assertThat(ticket.verify(TOKEN, issued, now.plus(13, ChronoUnit.HOURS))).isFalse();
    }

    @Test
    void theTicketNeverOutlivesTheLinkItself() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        Instant linkExpiry = now.plus(30, ChronoUnit.MINUTES);

        String issued = ticket.issue(TOKEN, now, linkExpiry);

        assertThat(ticket.verify(TOKEN, issued, now.plus(29, ChronoUnit.MINUTES))).isTrue();
        assertThat(ticket.verify(TOKEN, issued, now.plus(31, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void tamperingWithTheExpiryInvalidatesTheSignature() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        String issued = ticket.issue(TOKEN, now, null);
        String signature = issued.substring(issued.indexOf('.') + 1);

        // 把过期时刻往后推十年，签名照旧——必须被拒
        String forged = (now.plus(3650, ChronoUnit.DAYS).getEpochSecond()) + "." + signature;

        assertThat(ticket.verify(TOKEN, forged, now)).isFalse();
    }

    @Test
    void aDifferentSecretProducesADifferentSignature() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        ShareTicket other = new ShareTicket("another-secret", Duration.ofHours(12));

        assertThat(other.verify(TOKEN, ticket.issue(TOKEN, now, null), now)).isFalse();
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        assertThat(ticket.verify(TOKEN, null, now)).isFalse();
        assertThat(ticket.verify(TOKEN, "", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "no-dot-here", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "notanumber.c2ln", now)).isFalse();
        assertThat(ticket.verify(TOKEN, "1755000000.@@@not-base64@@@", now)).isFalse();
    }

    @Test
    void aBlankConfiguredSecretFallsBackToARandomOne() {
        ShareTicket randomised = new ShareTicket("", Duration.ofHours(12));
        Instant now = Instant.parse("2026-08-17T10:00:00Z");

        // 自己签自己验必须通过；跨实例不通过（重启后旧票据失效，这是已知取舍）
        String issued = randomised.issue(TOKEN, now, null);
        assertThat(randomised.verify(TOKEN, issued, now)).isTrue();
        assertThat(new ShareTicket("", Duration.ofHours(12)).verify(TOKEN, issued, now)).isFalse();
    }
}
