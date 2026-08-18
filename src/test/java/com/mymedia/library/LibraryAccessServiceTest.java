package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryAccessServiceTest extends AbstractIntegrationTest {

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    private String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniquePath() {
        return "/media/" + UUID.randomUUID();
    }

    @Test
    void regularUserHasNoAccessByDefault() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isFalse();
    }

    @Test
    void grantedUserHasAccess() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isTrue();
    }

    @Test
    void revokedUserLosesAccess() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());
        accessService.revoke(user.getId(), library.getId());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isFalse();
    }

    @Test
    void adminHasImplicitAccessToEveryLibrary() {
        UserAccount admin = registrationService.register(uniqueName(), "pw", UserRole.ADMIN);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        // 从未 grant 过，但 ADMIN 隐式拥有全部访问权
        assertThat(accessService.canAccess(admin.getId(), library.getId())).isTrue();
    }

    @Test
    void accessibleLibrariesListsOnlyGrantedOnes() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary granted = libraryService.create("已授权", LibraryDomain.VIDEO, uniquePath());
        libraryService.create("未授权", LibraryDomain.IMAGE, uniquePath());

        accessService.grant(user.getId(), granted.getId());

        assertThat(accessService.accessibleLibraries(user.getId()))
                .extracting(MediaLibrary::getId)
                .containsExactly(granted.getId());
    }

    @Test
    void grantIsIdempotent() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());
        accessService.grant(user.getId(), library.getId());   // 重复授权不应报错

        assertThat(accessService.canAccess(user.getId(), library.getId())).isTrue();
    }
}
