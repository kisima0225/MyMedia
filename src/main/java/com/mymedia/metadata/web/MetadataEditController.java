package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户编辑元数据与字段锁定。
 *
 * <p>URL 仍然按领域切分（{@code /api/video/items/...} 与 {@code /api/image/nodes/...}），
 * 但实现住在 {@code metadata} 模块：<b>接口按领域切分是对外部 URL 的要求，
 * 不要求实现类住在哪个模块</b>。两个域的编辑用的是同一套字段模型与同一套锁定语义，
 * 放在一起省掉一份重复的 DTO 与控制器，也保住了"领域模块不引用 metadata"的方向。
 */
@RestController
class MetadataEditController {

    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    MetadataEditController(VideoCatalogService videoCatalog,
                           ImageCatalogService imageCatalog,
                           LibraryAccessService accessService,
                           UserQueryService userQueryService) {
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/video/items/{id}/metadata")
    MetadataDto.Response videoMetadata(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id) {
        requireVideoAccess(principal, id);
        return MetadataDto.Response.from(videoCatalog.metadataOf(id));
    }

    @PutMapping("/api/video/items/{id}/metadata")
    MetadataDto.Response editVideoMetadata(@AuthenticationPrincipal UserDetails principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody MetadataDto.EditRequest request) {
        requireVideoAccess(principal, id);
        videoCatalog.applyUserEdit(id, request.fields());
        return MetadataDto.Response.from(videoCatalog.metadataOf(id));
    }

    @GetMapping("/api/image/nodes/{id}/metadata")
    MetadataDto.Response imageMetadata(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id) {
        requireImageAccess(principal, id);
        return MetadataDto.Response.from(imageCatalog.metadataOf(id));
    }

    @PutMapping("/api/image/nodes/{id}/metadata")
    MetadataDto.Response editImageMetadata(@AuthenticationPrincipal UserDetails principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody MetadataDto.EditRequest request) {
        requireImageAccess(principal, id);
        imageCatalog.applyUserEdit(id, request.fields());
        return MetadataDto.Response.from(imageCatalog.metadataOf(id));
    }

    private void requireVideoAccess(UserDetails principal, Long itemId) {
        Long libraryId = videoCatalog.getItem(itemId).getLibraryId();
        if (!accessService.canAccess(currentUserId(principal), libraryId)) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
    }

    private void requireImageAccess(UserDetails principal, Long nodeId) {
        Long libraryId = imageCatalog.getNode(nodeId).getLibraryId();
        if (!accessService.canAccess(currentUserId(principal), libraryId)) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
