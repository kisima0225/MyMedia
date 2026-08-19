package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoFileRepository extends JpaRepository<VideoFile, Long> {

    Optional<VideoFile> findByScannedFileId(Long scannedFileId);

    List<VideoFile> findByItemIdOrderBySortKey(Long itemId);

    List<VideoFile> findByGroupIdOrderByEpisodeIndex(Long groupId);
}
