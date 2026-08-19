package com.mymedia.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VideoFolderRepository extends JpaRepository<VideoFolder, Long> {

    Optional<VideoFolder> findByLibraryIdAndParentIdAndName(Long libraryId, Long parentId, String name);

    Optional<VideoFolder> findByLibraryIdAndParentIdIsNullAndName(Long libraryId, String name);

    List<VideoFolder> findByLibraryIdAndParentIdOrderBySortKey(Long libraryId, Long parentId);

    List<VideoFolder> findByLibraryIdAndParentIdIsNullOrderBySortKey(Long libraryId);

    Optional<VideoFolder> findByLibraryIdAndId(Long libraryId, Long id);

    List<VideoFolder> findAllByLibraryIdAndIdIn(Long libraryId, List<Long> ids);
}
