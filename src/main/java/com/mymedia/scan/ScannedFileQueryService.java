package com.mymedia.scan;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@code scan} 模块对外暴露的物理文件查询能力。
 * 领域模块通过它拿到文件路径与元信息，但不能修改物理层状态。
 */
@Service
public class ScannedFileQueryService {

    private final ScannedFileRepository repository;

    ScannedFileQueryService(ScannedFileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ScannedFile getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到扫描文件 id=" + id));
    }

    @Transactional(readOnly = true)
    public Optional<ScannedFile> findByPath(Long libraryId, String relativePath) {
        return repository.findByLibraryIdAndRelativePath(libraryId, relativePath);
    }

    @Transactional(readOnly = true)
    public long countActive(Long libraryId) {
        return repository.countByLibraryIdAndStatus(libraryId, ScannedFileStatus.ACTIVE);
    }
}
