package com.mymedia.image.web;

import com.mymedia.image.ImageBrowseService;
import com.mymedia.image.ImageNode;
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

        List<ImageNodeDto.NodeSummary> breadcrumb = List.of();
        if (nodeId != null) {
            ImageNode node = browseService.getNode(nodeId);
            if (!accessService.canAccess(userId, node.getLibraryId())
                    || !node.getLibraryId().equals(libraryId)) {
                // 404 而非 403：不泄露资源存在性
                throw new NotFoundException("找不到图片节点 id=" + nodeId);
            }
            breadcrumb = browseService.breadcrumb(nodeId).stream()
                    .map(ImageNodeDto.NodeSummary::from).toList();
        }

        return new ImageNodeDto.BrowseResponse(
                breadcrumb,
                browseService.childNodes(libraryId, nodeId).stream()
                        .map(ImageNodeDto.NodeSummary::from).toList());
    }
}
