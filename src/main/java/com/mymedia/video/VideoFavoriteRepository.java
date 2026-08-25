package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface VideoFavoriteRepository extends JpaRepository<VideoFavorite, VideoFavorite.Key> {

    List<VideoFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);
}
