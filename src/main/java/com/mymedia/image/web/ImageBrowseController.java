package com.mymedia.image.web;

import com.mymedia.image.ImageBrowseService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/image/browse")
class ImageBrowseController {

    private final ImageBrowseService browseService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageBrowseController(ImageBrowseService browseService,
                          LibraryAccessService accessService,
                          UserQueryService userQueryService) {
        this.browseService = browseService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    ImageNodeDto.BrowseResponse browse(@AuthenticationPrincipal UserDetails principal,
                                       @RequestParam Long libraryId,
                                       @RequestParam(required = false) Long nodeId) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        if (!accessService.canAccess(userId, libraryId)) {
            throw new NotFoundException("找不到媒体库 id=" + libraryId);
        }

        List<ImageNodeDto.NodeSummary> breadcrumb = nodeId == null
                ? List.of()
                : browseService.breadcrumb(nodeId).stream()
                        .map(ImageNodeDto.NodeSummary::from).toList();

        return new ImageNodeDto.BrowseResponse(
                breadcrumb,
                browseService.childNodes(libraryId, nodeId).stream()
                        .map(ImageNodeDto.NodeSummary::from).toList());
    }
}