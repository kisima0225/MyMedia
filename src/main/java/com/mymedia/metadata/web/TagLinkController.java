package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.Tag;
import com.mymedia.metadata.TagService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoItem;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 给条目打标签，以及按标签浏览。
 *
 * <p>URL 按领域切分（{@code /api/video/items/…}、{@code /api/image/nodes/…}），
 * 实现住在 {@code metadata}——与计划 05 的元数据编辑端点同一个理由：
 * 接口按领域切分是对外部 URL 的要求，不要求实现类住在哪个模块。
 */
@RestController
class TagLinkController {

    private static final int MAX_LIMIT = 200;

    private final TagService tagService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    TagLinkController(TagService tagService,
                      VideoCatalogService videoCatalog,
                      ImageCatalogService imageCatalog,
                      LibraryAccessService accessService,
                      UserQueryService userQueryService) {
        this.tagService = tagService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/video/items/{id}/tags")
    List<TagDto.Response> videoTags(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        requireVideoAccess(principal, id);
        return tagService.tagsOf(LibraryDomain.VIDEO, id).stream()
                .map(TagDto.Response::from).toList();
    }

    @PutMapping("/api/video/items/{id}/tags")
    List<TagDto.Response> setVideoTags(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody TagDto.SetTagsRequest request) {
        requireVideoAccess(principal, id);
        return tagService.setTags(LibraryDomain.VIDEO, id, request.tagIds()).stream()
                .map(TagDto.Response::from).toList();
    }

    @GetMapping("/api/image/nodes/{id}/tags")
    List<TagDto.Response> imageTags(@AuthenticationPrincipal UserDetails principal,
                                    @PathVariable Long id) {
        requireImageAccess(principal, id);
        return tagService.tagsOf(LibraryDomain.IMAGE, id).stream()
                .map(TagDto.Response::from).toList();
    }

    @PutMapping("/api/image/nodes/{id}/tags")
    List<TagDto.Response> setImageTags(@AuthenticationPrincipal UserDetails principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody TagDto.SetTagsRequest request) {
        requireImageAccess(principal, id);
        return tagService.setTags(LibraryDomain.IMAGE, id, request.tagIds()).stream()
                .map(TagDto.Response::from).toList();
    }

    /** 按标签列条目。标签自己带 domain，所以不需要调用方再传一次。 */
    @GetMapping("/api/tags/{id}/items")
    List<TagDto.TaggedTarget> targets(@AuthenticationPrincipal UserDetails principal,
                                      @PathVariable Long id,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Long userId = currentUserId(principal);
        Tag tag = tagService.getById(id);
        List<Long> targetIds = tagService.targetIdsWithTag(id, Math.clamp(limit, 1, MAX_LIMIT));

        if (tag.getDomain() == LibraryDomain.VIDEO) {
            return videoCatalog.findByIds(targetIds).stream()
                    .filter(item -> accessService.canAccess(userId, item.getLibraryId()))
                    .map(item -> new TagDto.TaggedTarget(
                            item.getId(), item.getTitle(), item.getCoverAssetId()))
                    .toList();
        }
        return imageCatalog.findByIds(targetIds).stream()
                .filter(node -> accessService.canAccess(userId, node.getLibraryId()))
                .map(node -> new TagDto.TaggedTarget(
                        node.getId(), node.getDisplayName(), node.getCoverAssetId()))
                .toList();
    }

    private void requireVideoAccess(UserDetails principal, Long itemId) {
        VideoItem item = videoCatalog.getItem(itemId);
        if (!accessService.canAccess(currentUserId(principal), item.getLibraryId())) {
            throw new NotFoundException("找不到视频条目 id=" + itemId);
        }
    }

    private void requireImageAccess(UserDetails principal, Long nodeId) {
        ImageNode node = imageCatalog.getNode(nodeId);
        if (!accessService.canAccess(currentUserId(principal), node.getLibraryId())) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
