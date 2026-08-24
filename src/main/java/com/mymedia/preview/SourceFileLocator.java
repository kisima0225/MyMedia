package com.mymedia.preview;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 物理文件 id → 磁盘绝对路径。
 *
 * <p>返回空表示"这个文件现在拿不到"——扫描把消失的文件标成 {@code MISSING}
 * 而不删除（外接盘没挂载是常态），预览生成遇到这种情况应当安静跳过，
 * 而不是抛异常让任务反复重试。
 */
@Component
class SourceFileLocator {

    private static final Logger log = LoggerFactory.getLogger(SourceFileLocator.class);

    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;

    SourceFileLocator(ScannedFileQueryService scannedFiles, LibraryService libraryService) {
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
    }

    Optional<Path> locate(Long scannedFileId) {
        ScannedFile file = scannedFiles.getById(scannedFileId);
        if (file.getStatus() != ScannedFileStatus.ACTIVE) {
            log.debug("跳过预览生成：文件已标记 MISSING，scannedFileId={}", scannedFileId);
            return Optional.empty();
        }
        Path path = Path.of(libraryService.getById(file.getLibraryId()).getRootPath())
                .resolve(file.getRelativePath());
        if (!Files.isReadable(path)) {
            log.debug("跳过预览生成：文件不可读 {}", path);
            return Optional.empty();
        }
        return Optional.of(path);
    }
}
