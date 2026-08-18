package com.mymedia.user;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserQueryService {

    private final UserAccountRepository repository;

    UserQueryService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return repository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public UserAccount getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到用户 id=" + id));
    }
}
