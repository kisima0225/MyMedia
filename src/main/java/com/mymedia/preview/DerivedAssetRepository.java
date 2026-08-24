package com.mymedia.preview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface DerivedAssetRepository extends JpaRepository<DerivedAsset, Long> {

    Optional<DerivedAsset> findByKindAndSourceScannedFileId(DerivedAssetKind kind, Long sourceScannedFileId);

    List<DerivedAsset> findBySourceScannedFileId(Long sourceScannedFileId);
}
