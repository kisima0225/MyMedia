package com.mymedia.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
