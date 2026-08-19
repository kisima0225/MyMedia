package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoGroupRepository extends JpaRepository<VideoGroup, Long> {

    Optional<VideoGroup> findByItemIdAndGroupIndex(Long itemId, int groupIndex);

    List<VideoGroup> findByItemIdOrderBySortKey(Long itemId);
}
