package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * 单页的定位、鉴权与开流。
 *
 * <p>散图与压缩包内页走<b>同一个端点</b>，对前端完全透明——
 * 阅读器不需要知道这本书是一个目录还是一个 CBZ。
 */
@Service
public class ImagePageService {

    private final ImageCatalogService catalogService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final LibraryAccessService accessService;
    private final ImageArchiveReader archiveReader;

    ImagePageService(ImageCatalogService catalogService,
                     ScannedFileQueryService scannedFiles,
                     LibraryService libraryService,
                     LibraryAccessService accessService,
                     ImageArchiveReader archiveReader) {
        this.catalogService = catalogService;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.accessService = accessService;
        this.archiveReader = archiveReader;
    }

    /**
     * 定位物理文件并校验访问权。
     *
     * <p>无权访问一律抛 {@link NotFoundException} 而非权限异常——
     * 返回 403 会泄露「这个 id 确实存在」。
     */
    @Transactional(readOnly = true)
    public PageTarget locate(Long userId, Long fileId) {
        ImageFile page = catalogService.getFile(fileId);
        ScannedFile scanned = scannedFiles.getById(page.getScannedFileId());

        if (!accessService.canAccess(userId, scanned.getLibraryId())) {
            throw new NotFoundException("找不到图片 id=" + fileId);
        }
        if (scanned.getStatus() == ScannedFileStatus.MISSING) {
            throw new NotFoundException(
                    "文件当前不可用（可能所在磁盘未挂载）: " + scanned.getRelativePath());
        }

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path path = root.resolve(scanned.getRelativePath());

        // ETag 由页 id + 物理文件大小 + 修改时间构成：底层文件一变 ETag 必变，
        // 客户端缓存的旧页就会被判为过期。
        String etag = "\"" + page.getId() + "-" + scanned.getSizeBytes()
                + "-" + scanned.getMtime().toEpochMilli() + "\"";

        String nameForType = page.getArchiveEntryName() == null
                ? scanned.getRelativePath()
                : page.getArchiveEntryName();

        return new PageTarget(path, page.getArchiveEntryName(),
                page.getArchiveEntryName() == null ? scanned.getSizeBytes() : -1,
                etag, scanned.getMtime(), contentTypeOf(nameForType));
    }

    /** 打开页的字节流。调用方负责关闭。 */
    public InputStream open(PageTarget target) throws IOException {
        return target.archiveEntryName() == null
                ? Files.newInputStream(target.path())
                : archiveReader.openEntry(target.path(), target.archiveEntryName());
    }

    private static String contentTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /** {@code archiveEntryName} 为 null 表示散图；{@code sizeBytes} 为 -1 表示大小未知。 */
    public record PageTarget(
            Path path,
            String archiveEntryName,
            long sizeBytes,
            String etag,
            Instant lastModified,
            String contentType) {
    }
}