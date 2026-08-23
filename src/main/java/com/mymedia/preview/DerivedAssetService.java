package com.mymedia.preview;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 派生资源的登记与查询，是 {@code preview} 模块对外的资源入口。
 */
@Service
public class DerivedAssetService {

    private final DerivedAssetRepository repository;
    private final DerivedAssetStorage storage;

    DerivedAssetService(DerivedAssetRepository repository, DerivedAssetStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** 建好目录并返回该资源应当写入的绝对路径。 */
    public Path prepare(DerivedAssetKind kind, Long sourceScannedFileId) throws IOException {
        return storage.prepare(kind, sourceScannedFileId);
    }

    /**
     * 把已经写到磁盘上的文件登记入库，大小从磁盘现读。
     *
     * <p><b>幂等</b>：同一个 (来源文件, 种类) 重复调用只更新既有行。
     * 重新生成不能产生第二行，否则 {@code cover_asset_id} 会指向一个
     * 已经被覆盖掉的旧文件。
     */
    @Transactional
    public DerivedAsset record(DerivedAssetKind kind, Long sourceScannedFileId,
                               Integer width, Integer height) {
        String relativePath = storage.relativePathOf(kind, sourceScannedFileId);
        Path file = storage.resolve(relativePath);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("派生资源文件不存在或不可读: " + file, e);
        }

        DerivedAsset asset = repository
                .findByKindAndSourceScannedFileId(kind, sourceScannedFileId)
                .orElseGet(() -> new DerivedAsset(kind, sourceScannedFileId, relativePath));
        asset.refresh(width, height, size);
        return repository.saveAndFlush(asset);
    }

    @Transactional(readOnly = true)
    public Optional<DerivedAsset> find(DerivedAssetKind kind, Long sourceScannedFileId) {
        return repository.findByKindAndSourceScannedFileId(kind, sourceScannedFileId);
    }

    @Transactional(readOnly = true)
    public DerivedAsset getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到派生资源 id=" + id));
    }

    public Path pathOf(DerivedAsset asset) {
        return storage.resolve(asset.getRelativePath());
    }
}