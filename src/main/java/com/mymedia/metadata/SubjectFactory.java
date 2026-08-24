package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImageSourceKind;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把两个域的条目抹平成 {@link ScrapeSubject}。
 *
 * <p>这个类把域间差异集中在一处：视频使用主文件，图片目录使用首页，
 * 图片归档则直接使用扫描时记录的压缩包本体。
 */
@Component
class SubjectFactory {

    /** 从路径里认年份：1900–2099，避免把 1080p 之类的数字当成年份。 */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;

    SubjectFactory(VideoCatalogService videoCatalog,
                   ImageCatalogService imageCatalog,
                   ScannedFileQueryService scannedFiles,
                   LibraryService libraryService) {
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
    }

    ScrapeSubject create(LibraryDomain domain, Long targetId) {
        return domain == LibraryDomain.VIDEO ? forVideo(targetId) : forImage(targetId);
    }

    private ScrapeSubject forVideo(Long itemId) {
        VideoItem item = videoCatalog.getItem(itemId);
        List<VideoFile> files = videoCatalog.filesOf(itemId);

        Path path = null;
        Integer year = null;
        if (!files.isEmpty()) {
            String relativePath = scannedFiles.getById(files.get(0).getScannedFileId())
                    .getRelativePath();
            path = rootOf(item.getLibraryId()).resolve(relativePath);
            year = yearIn(relativePath);
        }
        return new ScrapeSubject(LibraryDomain.VIDEO, itemId, item.getLibraryId(),
                item.getTitle(), year, path);
    }

    private ScrapeSubject forImage(Long nodeId) {
        ImageNode node = imageCatalog.getNode(nodeId);

        Path path = null;
        if (node.getSourceKind() == ImageSourceKind.ARCHIVE
                && node.getArchiveScannedFileId() != null) {
            // ARCHIVE_INDEX 还没填页时，节点本身仍然能定位到压缩包。
            path = rootOf(node.getLibraryId()).resolve(
                    scannedFiles.getById(node.getArchiveScannedFileId()).getRelativePath());
        } else {
            List<ImageFile> pages = imageCatalog.pagesOf(nodeId);
            if (!pages.isEmpty()) {
                ImageFile firstPage = pages.get(0);
                Path filePath = rootOf(node.getLibraryId()).resolve(
                        scannedFiles.getById(firstPage.getScannedFileId()).getRelativePath());
                // 散图目录的主体是目录；归档节点的主体是压缩包本身。
                path = firstPage.getArchiveEntryName() != null ? filePath : filePath.getParent();
            }
        }
        String title = node.getTitle() == null || node.getTitle().isBlank()
                ? node.getName() : node.getTitle();
        return new ScrapeSubject(LibraryDomain.IMAGE, nodeId, node.getLibraryId(),
                title, null, path);
    }

    private Path rootOf(Long libraryId) {
        return Path.of(libraryService.getById(libraryId).getRootPath());
    }

    private static Integer yearIn(String relativePath) {
        Matcher matcher = YEAR.matcher(relativePath);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }
}
