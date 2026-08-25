package com.mymedia.image;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface ImageFavoriteRepository extends JpaRepository<ImageFavorite, ImageFavorite.Key> {

    @Query("""
            SELECT f FROM ImageFavorite f
            WHERE f.userId = :userId
            ORDER BY f.createdAt DESC
            """)
    List<ImageFavorite> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, Pageable pageable);
}
