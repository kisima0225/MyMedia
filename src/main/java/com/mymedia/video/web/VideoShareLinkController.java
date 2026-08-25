package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
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
 * 为一个视频条目创建分享链接。
 *
 * <p>住在 {@code video} 而不是 {@code library}：创建前必须确认
 * 「这个条目存在、而且你有权访问它」，那需要 {@link VideoCatalogService}，
 * 而 {@code library} 永远不许依赖 {@code video}。
 */
@RestController
class VideoShareLinkController {

    private final ShareLinkService shareLinkService;
    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoShareLinkController(ShareLinkService shareLinkService,
                             VideoCatalogService catalogService,
                             LibraryAccessService accessService,
                             UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/api/video/items/{id}/share")
    @ResponseStatus(HttpStatus.CREATED)
    ShareLinkDto.Response create(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id,
                                 @Valid @RequestBody ShareLinkDto.CreateRequest request) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        VideoItem item = catalogService.getItem(id);
        if (!accessService.canAccess(userId, item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + id);
        }
        return ShareLinkDto.Response.from(
                shareLinkService.createForVideoItem(userId, item.getLibraryId(), id, request));
    }
}
