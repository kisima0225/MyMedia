package com.mymedia.preview.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.preview.DerivedAsset;
import com.mymedia.preview.DerivedAssetService;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 派生资源的统一访问入口，访问权沿来源扫描文件所属媒体库判断。 */
@RestController
@RequestMapping("/api/assets")
class AssetController {

    private static final String CACHE_CONTROL = "private, max-age=604800";

    private final DerivedAssetService assetService;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    AssetController(DerivedAssetService assetService,
                    ScannedFileQueryService scannedFiles,
                    LibraryAccessService accessService,
                    UserQueryService userQueryService) {
        this.assetService = assetService;
        this.scannedFiles = scannedFiles;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{id}")
    ResponseEntity<StreamingResponseBody> asset(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {

        DerivedAsset asset = assetService.getById(id);
        Long libraryId = scannedFiles.getById(asset.getSourceScannedFileId()).getLibraryId();
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到派生资源 id=" + id);
        }

        String etag = "\"asset-" + asset.getId() + "-"
                + asset.getGeneratedAt().toEpochMilli() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        Path path = assetService.pathOf(asset);
        if (!Files.isReadable(path)) {
            throw new NotFoundException("派生资源文件不存在 id=" + id);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_TYPE, asset.getKind().contentType())
                .contentLength(asset.getSizeBytes())
                .body(writer(path));
    }

    private static StreamingResponseBody writer(Path path) {
        return (OutputStream out) -> {
            try (InputStream in = Files.newInputStream(path)) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // 客户端提前断开时响应流关闭是正常行为。
            }
        };
    }
}
