package com.mymedia.image.web;

import com.mymedia.image.ImageBrowseService;
import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageNode;
import com.mymedia.image.ImagePageService;
import com.mymedia.library.ShareGrant;
import com.mymedia.library.ShareLinkService;
import com.mymedia.shared.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 分享链接下的图片访问。<b>整段免登录</b>。
 *
 * <p>分享的是<b>一个节点及其整棵子树</b>：分享「某画师」应当能一路翻到
 * 它下面每一本。{@code nodeId} 参数允许在子树内导航，越界一律 404。
 */
@RestController
@RequestMapping("/api/share/{token}/image")
class ImageShareController {

    private static final String TICKET_HEADER = "X-Share-Ticket";

    private final ShareLinkService shareLinkService;
    private final ImageCatalogService catalogService;
    private final ImageBrowseService browseService;
    private final ImagePageService pageService;

    ImageShareController(ShareLinkService shareLinkService,
                         ImageCatalogService catalogService,
                         ImageBrowseService browseService,
                         ImagePageService pageService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.browseService = browseService;
        this.pageService = pageService;
    }

    /**
     * 子树内的一个节点：它自己、它的子节点、它直接持有的页。
     *
     * @param nodeId 省略时就是被分享的那个节点
     */
    @GetMapping("/node")
    ShareNodeView node(@PathVariable String token,
                       @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
                       @RequestParam(value = "nodeId", required = false) Long nodeId) {
        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        ImageNode node = requireWithinShare(grant, nodeId);

        return new ShareNodeView(
                ImageNodeDto.NodeSummary.from(node),
                browseService.childNodes(node.getLibraryId(), node.getId()).stream()
                        .map(ImageNodeDto.NodeSummary::from)
                        .toList(),
                catalogService.pagesOf(node.getId()).stream()
                        .map(ImageNodeDto.PageSummary::from)
                        .toList());
    }

    @GetMapping("/pages/{fileId}")
    ResponseEntity<InputStreamResource> page(
            @PathVariable String token,
            @PathVariable Long fileId,
            @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {

        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        if (!grant.isImage()) {
            throw new NotFoundException("分享链接不存在或已失效");
        }
        ImagePageService.PageTarget target = pageService.locateForShare(grant, fileId);

        if (target.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpServletResponse.SC_NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, target.etag())
                .header(HttpHeaders.CONTENT_TYPE, target.contentType())
                .body(new InputStreamResource(pageService.open(target)));
    }

    /** 请求的节点必须落在被分享的子树里；否则 404，不区分「不存在」与「越界」。 */
    private ImageNode requireWithinShare(ShareGrant grant, Long nodeId) {
        if (!grant.isImage()) {
            throw new NotFoundException("分享链接不存在或已失效");
        }
        ImageNode shared = catalogService.getNode(grant.imageNodeId());
        if (nodeId == null || nodeId.equals(shared.getId())) {
            return shared;
        }
        ImageNode requested = catalogService.getNode(nodeId);
        if (!requested.getMaterializedPath().startsWith(shared.getMaterializedPath())) {
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }
        return requested;
    }

    /** 分享视图的一屏：节点自己 + 子节点 + 页。 */
    record ShareNodeView(ImageNodeDto.NodeSummary node,
                         List<ImageNodeDto.NodeSummary> children,
                         List<ImageNodeDto.PageSummary> pages) {
    }
}
