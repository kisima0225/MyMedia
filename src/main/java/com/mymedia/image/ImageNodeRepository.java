package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ImageNodeRepository extends JpaRepository<ImageNode, Long> {

    Optional<ImageNode> findByLibraryIdAndParentIdAndName(Long libraryId, Long parentId, String name);

    Optional<ImageNode> findByLibraryIdAndParentIdIsNullAndName(Long libraryId, String name);

    Optional<ImageNode> findByArchiveScannedFileId(Long archiveScannedFileId);

    List<ImageNode> findByParentIdOrderBySortKey(Long parentId);

    List<ImageNode> findByLibraryIdAndParentIdIsNullOrderBySortKey(Long libraryId);

    List<ImageNode> findByLibraryId(Long libraryId);
}