package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLinkServiceTest extends AbstractIntegrationTest {

    @Autowired
    ShareLinkService shareLinkService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    private MediaLibrary library;
    private Long itemId;
    private Long ownerId;

    private static final ShareLinkDto.CreateRequest PLAIN =
            new ShareLinkDto.CreateRequest(null, null);

    private Long insertItem(Long libraryId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', ?, ?) RETURNING id
                """, Long.class, libraryId, title, title);
    }

    @BeforeEach
    void setUp() {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                "/tmp/" + UUID.randomUUID());
        itemId = insertItem(library.getId(), "沙漠风暴");

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private ShareLink create() {
        return shareLinkService.createForVideoItem(ownerId, library.getId(), itemId, PLAIN);
    }

    @Test
    void tokenIsUrlSafeAndLongEnoughToResistGuessing() {
        String token = create().getToken();

        // 32 字节 Base64URL 无填充 = 43 个字符，字符集只有 A-Za-z0-9-_
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void everyLinkGetsADistinctToken() {
        assertThat(create().getToken()).isNotEqualTo(create().getToken());
    }

    @Test
    void resolvesToAGrantPointingAtTheSharedItem() {
        ShareLink link = create();

        ShareGrant grant = shareLinkService.resolve(link.getToken());

        assertThat(grant.shareLinkId()).isEqualTo(link.getId());
        assertThat(grant.libraryId()).isEqualTo(library.getId());
        assertThat(grant.videoItemId()).isEqualTo(itemId);
        assertThat(grant.imageNodeId()).isNull();
        assertThat(grant.isVideo()).isTrue();
        assertThat(grant.passwordProtected()).isFalse();
    }

    @Test
    void aRevokedTokenResolvesToTheSameNotFoundAsAnUnknownOne() {
        ShareLink link = create();
        shareLinkService.revoke(ownerId, link.getId());

        // 区分「不存在」与「已失效」等于告诉扫链接的人「这个令牌曾经存在」
        assertThatThrownBy(() -> shareLinkService.resolve(link.getToken()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> shareLinkService.resolve("HHiVX3lHTuKrH0P-Y8sJ0dnhkFYCkDBPa2b7pt2X0Kg"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anExpiredTokenIsNotFound() {
        ShareLink link = create();
        jdbc.update("UPDATE share_link SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), link.getId());

        assertThatThrownBy(() -> shareLinkService.resolve(link.getToken()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void expiresInDaysIsTurnedIntoAnAbsoluteInstant() {
        ShareLink link = shareLinkService.createForVideoItem(
                ownerId, library.getId(), itemId, new ShareLinkDto.CreateRequest(null, 7));

        assertThat(link.getExpiresAt())
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    void aPasswordIsStoredHashedAndNeverExposed() {
        ShareLink link = shareLinkService.createForVideoItem(
                ownerId, library.getId(), itemId, new ShareLinkDto.CreateRequest("hunter2", null));

        assertThat(link.isPasswordProtected()).isTrue();
        assertThat(shareLinkService.resolve(link.getToken()).passwordProtected()).isTrue();

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM share_link WHERE id = ?", String.class, link.getId());
        assertThat(stored).isNotNull().doesNotContain("hunter2").startsWith("{bcrypt}");
    }

    @Test
    void onlyTheCreatorCanRevoke() {
        ShareLink link = create();
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);

        assertThatThrownBy(() -> shareLinkService.revoke(stranger.getId(), link.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(shareLinkService.resolve(link.getToken())).isNotNull();
    }

    @Test
    void listOnlyReturnsMyOwnLinks() {
        create();
        UserAccount stranger = registrationService.register(
                "s" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        shareLinkService.createForVideoItem(stranger.getId(), library.getId(), itemId, PLAIN);

        assertThat(shareLinkService.listCreatedBy(ownerId))
                .hasSize(1)
                .allMatch(link -> link.getCreatedBy().equals(ownerId));
    }

    @Test
    void revokedLinksStayInTheListSoTheUserCanSeeWhatHappened() {
        ShareLink link = create();
        shareLinkService.revoke(ownerId, link.getId());

        assertThat(shareLinkService.listCreatedBy(ownerId))
                .singleElement()
                .satisfies(revoked -> assertThat(revoked.getRevokedAt()).isNotNull());
    }

    @Test
    void deletingTheTargetItemTakesItsShareLinksWithIt() {
        create();

        jdbc.update("DELETE FROM video_item WHERE id = ?", itemId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM share_link WHERE library_id = ?",
                Integer.class, library.getId())).isZero();
    }

    @Test
    void theDatabaseRefusesALinkThatPointsAtBothDomains() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO share_link (token, library_id, video_item_id, image_node_id, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, "tok" + UUID.randomUUID(), library.getId(), itemId, itemId, ownerId))
                .hasMessageContaining("ck_share_link_single_target");
    }
}
