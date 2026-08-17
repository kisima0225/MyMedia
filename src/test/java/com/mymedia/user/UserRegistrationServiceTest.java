package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRegistrationServiceTest extends AbstractIntegrationTest {

    @Autowired
    UserRegistrationService registrationService;

    @Test
    void registersUserWithHashedPassword() {
        UserAccount account = registrationService.register("alice", "s3cret", UserRole.USER);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getUsername()).isEqualTo("alice");
        assertThat(account.getRole()).isEqualTo(UserRole.USER);
        assertThat(account.isEnabled()).isTrue();
    }

    @Test
    void neverStoresRawPassword() {
        UserAccount account = registrationService.register("bob", "s3cret", UserRole.USER);

        assertThat(account.getPasswordHash()).doesNotContain("s3cret");
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}");
    }

    @Test
    void rejectsDuplicateUsername() {
        registrationService.register("carol", "pw1", UserRole.USER);

        assertThatThrownBy(() -> registrationService.register("carol", "pw2", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carol");
    }
}
