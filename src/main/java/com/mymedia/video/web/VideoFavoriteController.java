package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFavoriteService;
import com.mymedia.video.VideoItem;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class VideoFavoriteController {

    private static final int MAX_LIMIT = 200;

    private final VideoFavoriteService favoriteService;
    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoFavoriteController(VideoFavoriteService favoriteService,
                            VideoCatalogService catalogService,
                            LibraryAccessService accessService,
                            UserQueryService userQueryService) {
        this.favoriteService = favoriteService;
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/api/video/items/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.add(requireAccess(principal, id), id);
    }

    @DeleteMapping("/api/video/items/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        favoriteService.remove(requireAccess(principal, id), id);
    }

    @GetMapping("/api/video/favorites")
    List<VideoItem> list(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return favoriteService.listItems(currentUserId(principal), Math.clamp(limit, 1, MAX_LIMIT));
    }

    /** 校验访问权并返回当前用户 id。无权访问返回 404，不泄露资源存在性。 */
    private Long requireAccess(UserDetails principal, Long itemId) {
        Long userId = currentUserId(principal);
        VideoItem item = catalogService.getItem(itemId);
        if (!accessService.canAccess(userId, item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
        return userId;
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
