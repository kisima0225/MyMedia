package com.mymedia.image.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImageReadingMode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/image/nodes")
class ImageNodeController {

    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ImageNodeController(ImageCatalogService catalogService,
                        LibraryAccessService accessService,
                        UserQueryService userQueryService) {
        this.catalogService = catalogService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    /** 用户可访问的全部图片库的顶层节点。 */
    @GetMapping
    List<ImageNodeDto.NodeSummary> topLevel(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .filter(library -> library.getDomain() == LibraryDomain.IMAGE)
                .map(MediaLibrary::getId)
                .flatMap(libraryId -> catalogService.findRoots(libraryId).stream())
                .map(ImageNodeDto.NodeSummary::from)
                .toList();
    }

    @GetMapping("/{id}")
    ImageNodeDto.NodeSummary detail(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        return ImageNodeDto.NodeSummary.from(requireAccessible(principal, id));
    }

    @GetMapping("/{id}/pages")
    List<ImageNodeDto.PageSummary> pages(@AuthenticationPrincipal UserDetails principal,
                                         @PathVariable Long id) {
        requireAccessible(principal, id);
        return catalogService.pagesOf(id).stream()
                .map(ImageNodeDto.PageSummary::from)
                .toList();
    }

    /** 用户推翻自动判定。 */
    @PutMapping("/{id}/reading-mode")
    ImageNodeDto.NodeSummary setReadingMode(@AuthenticationPrincipal UserDetails principal,
                                            @PathVariable Long id,
                                            @RequestBody ImageNodeDto.ReadingModeRequest request) {
        requireAccessible(principal, id);
        return ImageNodeDto.NodeSummary.from(
                catalogService.setReadingMode(id, parseMode(request.mode())));
    }

    private static ImageReadingMode parseMode(String raw) {
        try {
            return ImageReadingMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "阅读模式只能是 AUTO / FORCE_BOOK / FORCE_FOLDER，收到: " + raw);
        }
    }

    private ImageNode requireAccessible(UserDetails principal, Long nodeId) {
        ImageNode node = catalogService.getNode(nodeId);
        if (!accessService.canAccess(currentUserId(principal), node.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
        return node;
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}