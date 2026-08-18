package com.mymedia.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

interface ScannedFileRepository extends JpaRepository<ScannedFile, Long> {

    Optional<ScannedFile> findByLibraryIdAndRelativePath(Long libraryId, String relativePath);

    List<ScannedFile> findByLibraryId(Long libraryId);

    long countByLibraryIdAndStatus(Long libraryId, ScannedFileStatus status);

    /** Persists a hash without merging a detached entity after filesystem I/O. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE scanned_file
            SET content_hash = :hash
            WHERE id = :id
              AND status = 'ACTIVE'
              AND content_hash IS NULL
            """, nativeQuery = true)
    int updateContentHash(@Param("id") Long id, @Param("hash") String hash);

    @Query("""
            SELECT f FROM ScannedFile f
            WHERE f.libraryId = :libraryId AND f.status = com.mymedia.scan.ScannedFileStatus.MISSING
            """)
    List<ScannedFile> findMissing(@Param("libraryId") Long libraryId);
}
