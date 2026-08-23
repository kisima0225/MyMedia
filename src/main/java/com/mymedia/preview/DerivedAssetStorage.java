package com.mymedia.preview;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 派生资源在磁盘上的布局。
 *
 * <p>路径形如 {@code covers/37/1337-cover.jpg}——按来源文件 id 取模做两级分片，
 * 避免几十万个文件堆在一个目录里（ext4 与 NTFS 在单目录十万级文件时目录项查找
 * 会明显退化）。
 *
 * <p>分片键用<b>来源文件 id</b> 而不是 {@code derived_asset.id}：前者在写文件之前
 * 就已知，后者要等插入之后才有。附带好处是重新生成会覆盖同一个路径，与
 * {@code UNIQUE (source_scanned_file_id, kind)} 完全对齐，不会留下孤儿文件。
 */
@Component
class DerivedAssetStorage {

    private static final int SHARD_COUNT = 100;

    private final Path root;

    DerivedAssetStorage(PreviewProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    String relativePathOf(DerivedAssetKind kind, Long sourceScannedFileId) {
        long shard = Math.floorMod(sourceScannedFileId, SHARD_COUNT);
        return kind.directory() + "/" + shard + "/" + kind.fileName(sourceScannedFileId);
    }

    Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    /** 建好父目录并返回输出路径，生成器直接往这个路径写就行。 */
    Path prepare(DerivedAssetKind kind, Long sourceScannedFileId) throws IOException {
        Path target = resolve(relativePathOf(kind, sourceScannedFileId));
        Files.createDirectories(target.getParent());
        return target;
    }
}