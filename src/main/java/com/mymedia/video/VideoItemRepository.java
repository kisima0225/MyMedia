package com.mymedia.video;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoItemRepository extends JpaRepository<VideoItem, Long> {

    Page<VideoItem> findByLibraryIdIn(List<Long> libraryIds, Pageable pageable);

    Optional<VideoItem> findByLibraryIdAndTitle(Long libraryId, String title);

    List<VideoItem> findByFolderId(Long folderId);

    List<VideoItem> findByLibraryIdAndFolderIdOrderBySortTitle(Long libraryId, Long folderId);

    long countByFolderId(Long folderId);
}
