package com.mymedia.image;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ImageFileRepository extends JpaRepository<ImageFile, Long> {

    Optional<ImageFile> findByScannedFileIdAndArchiveEntryNameIsNull(Long scannedFileId);

    List<ImageFile> findByScannedFileId(Long scannedFileId);

    List<ImageFile> findByNodeIdOrderByPageIndex(Long nodeId);

    long countByNodeId(Long nodeId);

    void deleteByScannedFileId(Long scannedFileId);

    @Query("""
            SELECT f FROM ImageFile f, ImageNode n
            WHERE f.nodeId = n.id
              AND n.libraryId = :libraryId
              AND n.materializedPath LIKE :pathPrefix
            ORDER BY n.sortPath, f.pageIndex
            """)
    List<ImageFile> findSubtreePages(@Param("libraryId") Long libraryId,
                                     @Param("pathPrefix") String pathPrefix);
}