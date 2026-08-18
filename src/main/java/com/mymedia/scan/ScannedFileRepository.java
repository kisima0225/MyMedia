package com.mymedia.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ScannedFileRepository extends JpaRepository<ScannedFile, Long> {

    Optional<ScannedFile> findByLibraryIdAndRelativePath(Long libraryId, String relativePath);

    List<ScannedFile> findByLibraryId(Long libraryId);

    long countByLibraryIdAndStatus(Long libraryId, ScannedFileStatus status);

    @Query("""
            SELECT f FROM ScannedFile f
            WHERE f.libraryId = :libraryId AND f.status = com.mymedia.scan.ScannedFileStatus.MISSING
            """)
    List<ScannedFile> findMissing(@Param("libraryId") Long libraryId);
}
