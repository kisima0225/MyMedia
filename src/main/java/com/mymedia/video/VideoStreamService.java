package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryService;
import com.mymedia.library.ShareGrant;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Service
public class VideoStreamService {

    private final VideoCatalogService catalogService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final LibraryAccessService accessService;

    VideoStreamService(VideoCatalogService catalogService,
                       ScannedFileQueryService scannedFiles,
                       LibraryService libraryService,
                       LibraryAccessService accessService) {
        this.catalogService = catalogService;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.accessService = accessService;
    }

    /**
     * 定位物理文件并校验访问权。
     *
     * <p>无权访问一律抛 {@link NotFoundException} 而非权限异常——
     * 返回 403 会泄露「这个 id 确实存在」。
     */
    @Transactional(readOnly = true)
    public StreamTarget locate(Long userId, Long fileId) {
        VideoFile videoFile = catalogService.getFile(fileId);
        ScannedFile scanned = scannedFiles.getById(videoFile.getScannedFileId());

        if (!accessService.canAccess(userId, scanned.getLibraryId())) {
            throw notFound(fileId);
        }
        return toTarget(videoFile, scanned);
    }

    /**
     * 分享链接的定位入口：<b>不查 {@code library_access}</b>，
     * 访问控制已经由令牌完成（{@code ShareLinkService.resolveUnlocked}）。
     *
     * <p>但**包含性必须在这里查**：一张指向条目 A 的分享链接不能被用来播条目 B 的文件。
     * 这道校验放在服务里而不是控制器里——控制器可能会有第二个入口，服务是必经之路。
     */
    @Transactional(readOnly = true)
    public StreamTarget locateForShare(ShareGrant grant, Long fileId) {
        VideoFile videoFile = catalogService.getFile(fileId);
        if (!Objects.equals(videoFile.getItemId(), grant.videoItemId())) {
            throw notFound(fileId);
        }
        return toTarget(videoFile, scannedFiles.getById(videoFile.getScannedFileId()));
    }

    private StreamTarget toTarget(VideoFile videoFile, ScannedFile scanned) {
        if (scanned.getStatus() != ScannedFileStatus.ACTIVE) {
            throw notFound(videoFile.getId());
        }

        Path root;
        try {
            root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        } catch (InvalidPathException | SecurityException e) {
            throw notFound(videoFile.getId());
        }
        Path path = resolveRegularFile(root, scanned.getRelativePath(), videoFile.getId());

        String etag = "\"" + scanned.getId() + "-" + scanned.getSizeBytes()
                + "-" + scanned.getMtime().toEpochMilli() + "\"";
        return new StreamTarget(path, scanned.getSizeBytes(), etag,
                scanned.getMtime(), contentTypeOf(scanned.getExtension()));
    }

    private static Path resolveRegularFile(Path configuredRoot, String relativePath, Long fileId) {
        if (relativePath == null) {
            throw notFound(fileId);
        }

        try {
            Path root = configuredRoot.toAbsolutePath().normalize();
            Path path = root.resolve(relativePath).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                throw notFound(fileId);
            }

            // Also verify the real path so a database path cannot use an in-root
            // symlink to escape the configured library root.
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || !Files.isRegularFile(realPath)) {
                throw notFound(fileId);
            }
            return realPath;
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw notFound(fileId);
        }
    }

    private static NotFoundException notFound(Long fileId) {
        return new NotFoundException("找不到视频文件 id=" + fileId);
    }

    private static String contentTypeOf(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mp4", "m4v" -> "video/mp4";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "wmv" -> "video/x-ms-wmv";
            case "flv" -> "video/x-flv";
            case "mpg", "mpeg" -> "video/mpeg";
            case "ts", "m2ts" -> "video/mp2t";
            default -> "application/octet-stream";
        };
    }

    public record StreamTarget(
            Path path,
            long sizeBytes,
            String etag,
            Instant lastModified,
            String contentType) {
    }
}
