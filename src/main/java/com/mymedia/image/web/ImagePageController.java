package com.mymedia.image.web;

import com.mymedia.image.ImagePageService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@RestController
@RequestMapping("/api/image/page")
class ImagePageController {

    /** 页的字节内容只会随底层文件变化而变，而那会改掉 ETag，所以可以放心缓存一天。 */
    private static final String CACHE_CONTROL = "private, max-age=86400";

    private static final Logger log = LoggerFactory.getLogger(ImagePageController.class);

    private final ImagePageService pageService;
    private final UserQueryService userQueryService;

    ImagePageController(ImagePageService pageService, UserQueryService userQueryService) {
        this.pageService = pageService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{fileId}")
    ResponseEntity<StreamingResponseBody> page(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();

        ImagePageService.PageTarget target = pageService.locate(userId, fileId);

        // 阅读器来回翻页会反复请求同一页；ETag 命中就一个字节都不用再传。
        // 这里手工比对而不靠 ShallowEtagHeaderFilter —— 后者要把整页读进内存
        // 才能算出摘要，正好抵消了流式输出的意义。
        if (target.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, target.etag())
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.ETAG, target.etag())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_TYPE, target.contentType());
        if (target.sizeBytes() >= 0) {
            response = response.contentLength(target.sizeBytes());
        }

        return response.body(writer(target));
    }

    /**
     * 流式写出。
     *
     * <p>压缩包内页走的是 {@code ZipFile.getInputStream}——按需解压单个条目，
     * <b>不解压整个归档</b>。流关闭时压缩包一并关闭。
     *
     * <p>虚拟线程承载这段阻塞 I/O，不占用平台线程。
     */
    private StreamingResponseBody writer(ImagePageService.PageTarget target) {
        return (OutputStream out) -> {
            InputStream in;
            try {
                in = pageService.open(target);
            } catch (IOException e) {
                // 打开失败（文件被删除、压缩包损坏）是服务端错误，必须留痕——
                // 此时响应头已提交，改不回错误码，只能记录后静默结束。
                log.warn("打开图片页失败: path={} entry={}",
                        target.path(), target.archiveEntryName(), e);
                return;
            }
            try (in) {
                in.transferTo(out);
            } catch (IOException e) {
                // 用户快速翻页会中断上一页的连接，这是正常行为不是错误。
                // 静默结束，避免日志被刷屏。
            }
        };
    }
}