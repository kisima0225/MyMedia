package com.mymedia.image.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为一个图片节点创建分享链接。理由同 {@code VideoShareLinkController}。
 *
 * <p>可以分享<b>任意节点</b>，包括纯目录——分享一个画师目录和分享一本漫画
 * 是同一件事，Task 9 的访问端点会按节点自身的能力决定给出什么。
 */
@RestController
class ImageShareLinkController {

    private final ShareLinkService shareLinkService;
    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageShareLinkController(ShareLinkService shareLinkService,
                             ImageCatalogService catalogService,
                             LibraryAccessService accessService,
                             UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/api/image/nodes/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    ShareLinkDto.Response create(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody ShareLinkDto.CreateRequest request) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        ImageNode node = catalogService.getNode(id);
        if (!accessService.canAccess(userId, node.getLibraryId())) {
            throw new NotFoundException("找不到图片节点 id=" + id);
        }
        return ShareLinkDto.Response.from(
                shareLinkService.createForImageNode(userId, node.getLibraryId(), id, request));
    }
}
