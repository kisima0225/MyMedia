package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video/items")
class VideoCatalogController {

    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoCatalogController(VideoCatalogService catalogService,
                           LibraryAccessService accessService,
                           UserQueryService userQueryService) {
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<VideoCatalogDto.ItemSummary> list(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.VIDEO)
                .map(MediaLibrary::getId)
                .flatMap(libraryId -> catalogService.findByLibrary(libraryId).stream())
                .map(VideoCatalogDto.ItemSummary::from)
                .toList();
    }

    @GetMapping("/{id}")
    VideoCatalogDto.ItemDetail detail(@AuthenticationPrincipal UserDetails principal,
                                      @PathVariable Long id) {
        VideoItem item = catalogService.getItem(id);
        requireAccess(principal, item);

        return new VideoCatalogDto.ItemDetail(
                VideoCatalogDto.ItemSummary.from(item),
                catalogService.groupsOf(id).stream()
                        .map(VideoCatalogDto.GroupSummary::from).toList(),
                catalogService.filesOf(id).stream()
                        .map(VideoCatalogDto.FileSummary::from).toList());
    }

    @GetMapping("/{id}/episodes")
    List<VideoCatalogDto.FileSummary> episodes(@AuthenticationPrincipal UserDetails principal,
                                               @PathVariable Long id) {
        VideoItem item = catalogService.getItem(id);
        requireAccess(principal, item);
        return catalogService.filesOf(id).stream()
                .map(VideoCatalogDto.FileSummary::from)
                .toList();
    }

    private void requireAccess(UserDetails principal, VideoItem item) {
        if (!accessService.canAccess(currentUserId(principal), item.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到视频条目 id=" + item.getId());
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
