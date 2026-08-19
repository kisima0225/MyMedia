package com.mymedia.video;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface VideoProgressRepository extends JpaRepository<VideoProgress, VideoProgress.Key> {

    Optional<VideoProgress> findByUserIdAndVideoFileId(Long userId, Long videoFileId);

    @Query("""
            SELECT p FROM VideoProgress p
            WHERE p.userId = :userId AND p.completed = false
            ORDER BY p.updatedAt DESC
            """)
    List<VideoProgress> findContinueWatching(@Param("userId") Long userId, Pageable pageable);
}
