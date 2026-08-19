package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.scan.ScannedFileStatus;
import com.mymedia.shared.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoStreamServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long FILE_ID = 11L;
    private static final Long SCANNED_FILE_ID = 13L;
    private static final Long LIBRARY_ID = 17L;
    private static final Instant MTIME = Instant.parse("2026-08-18T10:15:30Z");

    @TempDir
    Path root;

    @Test
    void locateReturnsActiveRegularFileInsideLibraryRoot() throws Exception {
        Path file = root.resolve("nested/movie.mkv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");

        VideoStreamService service = serviceFor(
                "nested/./movie.mkv", ScannedFileStatus.ACTIVE, 7L, "mkv");

        VideoStreamService.StreamTarget target = service.locate(USER_ID, FILE_ID);

        assertThat(target.path()).isEqualTo(file.toRealPath());
        assertThat(target.sizeBytes()).isEqualTo(7L);
        assertThat(target.etag()).isEqualTo("\"13-7-1787048130000\"");
        assertThat(target.lastModified()).isEqualTo(MTIME);
        assertThat(target.contentType()).isEqualTo("video/x-matroska");
    }

    @Test
    void missingFileIsNotStreamable() throws Exception {
        VideoStreamService service = serviceFor(
                "missing.mkv", ScannedFileStatus.MISSING, 0L, "mkv");

        assertThatThrownBy(() -> service.locate(USER_ID, FILE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void relativePathCannotEscapeLibraryRoot() throws Exception {
        Path outside = root.getParent().resolve("outside.mkv");
        Files.writeString(outside, "secret");
        VideoStreamService service = serviceFor(
                "../outside.mkv", ScannedFileStatus.ACTIVE, 6L, "mkv");

        assertThatThrownBy(() -> service.locate(USER_ID, FILE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void directoryIsNotStreamable() throws Exception {
        VideoStreamService service = serviceFor(
                "nested", ScannedFileStatus.ACTIVE, 0L, "mkv");
        Files.createDirectories(root.resolve("nested"));

        assertThatThrownBy(() -> service.locate(USER_ID, FILE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    private VideoStreamService serviceFor(String relativePath, ScannedFileStatus status,
                                           long sizeBytes, String extension) {
        VideoCatalogService catalogService = mock(VideoCatalogService.class);
        ScannedFileQueryService scannedFiles = mock(ScannedFileQueryService.class);
        LibraryService libraryService = mock(LibraryService.class);
        LibraryAccessService accessService = mock(LibraryAccessService.class);

        VideoFile videoFile = new VideoFile(SCANNED_FILE_ID, 19L, VideoFileRole.PRIMARY, relativePath);
        ScannedFile scanned = mock(ScannedFile.class);
        MediaLibrary library = mock(MediaLibrary.class);

        when(catalogService.getFile(FILE_ID)).thenReturn(videoFile);
        when(scannedFiles.getById(SCANNED_FILE_ID)).thenReturn(scanned);
        when(accessService.canAccess(USER_ID, LIBRARY_ID)).thenReturn(true);
        when(libraryService.getById(LIBRARY_ID)).thenReturn(library);
        when(library.getRootPath()).thenReturn(root.toString());
        when(scanned.getId()).thenReturn(SCANNED_FILE_ID);
        when(scanned.getLibraryId()).thenReturn(LIBRARY_ID);
        when(scanned.getRelativePath()).thenReturn(relativePath);
        when(scanned.getSizeBytes()).thenReturn(sizeBytes);
        when(scanned.getMtime()).thenReturn(MTIME);
        when(scanned.getExtension()).thenReturn(extension);
        when(scanned.getStatus()).thenReturn(status);

        return new VideoStreamService(catalogService, scannedFiles, libraryService, accessService);
    }
}
