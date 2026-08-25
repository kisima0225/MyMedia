package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ImageFavoriteRepository extends JpaRepository<ImageFavorite, ImageFavorite.Key> {

    List<ImageFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);
}
