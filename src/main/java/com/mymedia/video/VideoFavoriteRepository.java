package com.mymedia.video;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface VideoFavoriteRepository extends JpaRepository<VideoFavorite, VideoFavorite.Key> {

    @Query("""
            SELECT f FROM VideoFavorite f
            WHERE f.userId = :userId
            ORDER BY f.createdAt DESC
            """)
    List<VideoFavorite> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, Pageable pageable);
}
