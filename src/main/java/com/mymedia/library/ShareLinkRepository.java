package com.mymedia.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByCreatedByOrderByCreatedAtDesc(Long createdBy);
}
