package com.mymedia.video.web;

import com.mymedia.library.ShareGrant;
import com.mymedia.library.ShareLinkService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * 分享链接下的视频访问。<b>整段免登录</b>（{@code SecurityConfig} 里 permitAll）。
 *
 * <p>没有复制任何播放逻辑：定位走 {@code VideoStreamService.locateForShare}，
 * Range 应答走 {@code VideoRangeResponder}，与登录后的端点是同一段代码。
 * 唯一的差别是「凭什么允许你看」——一个查 {@code library_access}，
 * 一个查令牌与包含性。
 */
@RestController
@RequestMapping("/api/share/{token}/video")
class VideoShareController {

    /** 票据请求头。带密码的链接解锁后由客户端在每次请求上带回。 */
    private static final String TICKET_HEADER = "X-Share-Ticket";

    private final ShareLinkService shareLinkService;
    private final VideoCatalogService catalogService;
    private final VideoStreamService streamService;

    VideoShareController(ShareLinkService shareLinkService,
                         VideoCatalogService catalogService,
                         VideoStreamService streamService) {
        this.shareLinkService = shareLinkService;
        this.catalogService = catalogService;
        this.streamService = streamService;
    }

    @GetMapping("/item")
    VideoCatalogDto.ItemDetail item(@PathVariable String token,
                                    @RequestHeader(value = TICKET_HEADER, required = false)
                                    String ticket) {
        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        Long itemId = requireVideoTarget(grant);

        List<VideoCatalogDto.FileSummary> files = catalogService.filesOf(itemId).stream()
                .map(VideoCatalogDto.FileSummary::from)
                .toList();

        // 分享视图不给分组：一条链接指向一个条目，剧集分组属于库内浏览的形态
        return new VideoCatalogDto.ItemDetail(
                VideoCatalogDto.ItemSummary.from(catalogService.getItem(itemId)),
                List.of(),
                files);
    }

    @GetMapping("/stream/{fileId}")
    ResponseEntity<StreamingResponseBody> stream(
            @PathVariable String token,
            @PathVariable Long fileId,
            @RequestHeader(value = TICKET_HEADER, required = false) String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {

        ShareGrant grant = shareLinkService.resolveUnlocked(token, ticket);
        requireVideoTarget(grant);

        return VideoRangeResponder.respond(
                streamService.locateForShare(grant, fileId), rangeHeader, ifRange);
    }

    /**
     * 一张图片链接被拿来打视频端点时返回 404。
     *
     * <p>它确实存在，但在这个 URL 下不存在——而且既然对方拿错了域，
     * 告诉它「这是张图片链接」也没有意义。
     */
    private Long requireVideoTarget(ShareGrant grant) {
        if (!grant.isVideo()) {
            throw new com.mymedia.shared.NotFoundException("分享链接不存在或已失效");
        }
        return grant.videoItemId();
    }
}
