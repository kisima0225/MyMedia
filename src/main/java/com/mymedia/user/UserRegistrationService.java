package com.mymedia.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    UserRegistrationService(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserAccount register(String username, String rawPassword, UserRole role) {
        if (repository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已被占用: " + username);
        }
        String hash = passwordEncoder.encode(rawPassword);
        return repository.save(new UserAccount(username, hash, role));
    }
}
