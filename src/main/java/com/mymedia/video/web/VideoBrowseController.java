package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoBrowseService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video/browse")
class VideoBrowseController {

    private final VideoBrowseService browseService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoBrowseController(VideoBrowseService browseService,
                          LibraryAccessService accessService,
                          UserQueryService userQueryService) {
        this.browseService = browseService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    VideoBrowseDto.BrowseResponse browse(@AuthenticationPrincipal UserDetails principal,
                                         @RequestParam Long libraryId,
                                         @RequestParam(required = false) Long folderId) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        if (!accessService.canAccess(userId, libraryId)) {
            // 返回 404 而非 403：不向无权访问者泄露资源是否存在
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }

        List<VideoBrowseDto.FolderNode> breadcrumb = folderId == null
                ? List.of()
                : browseService.breadcrumb(libraryId, folderId).stream()
                        .map(VideoBrowseDto.FolderNode::from).toList();

        return new VideoBrowseDto.BrowseResponse(
                breadcrumb,
                browseService.childFolders(libraryId, folderId).stream()
                        .map(VideoBrowseDto.FolderNode::from).toList(),
                folderId == null
                        ? List.of()
                        : browseService.itemsIn(libraryId, folderId).stream()
                                .map(VideoBrowseDto.ItemNode::from).toList());
    }
}
