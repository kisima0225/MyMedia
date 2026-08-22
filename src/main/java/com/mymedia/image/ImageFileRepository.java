package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ImageFileRepository extends JpaRepository<ImageFile, Long> {

    Optional<ImageFile> findByScannedFileIdAndArchiveEntryNameIsNull(Long scannedFileId);

    List<ImageFile> findByScannedFileId(Long scannedFileId);

    List<ImageFile> findByNodeIdOrderByPageIndex(Long nodeId);

    long countByNodeId(Long nodeId);

    void deleteByScannedFileId(Long scannedFileId);
}