package com.mymedia.upload.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.upload.UploadSessionService;
import com.mymedia.user.UserQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/upload")
class UploadController {

    private final UploadSessionService sessionService;
    private final UserQueryService userQueryService;

    UploadController(UploadSessionService sessionService, UserQueryService userQueryService) {
        this.sessionService = sessionService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    UploadDto.Response create(@AuthenticationPrincipal UserDetails principal,
                              @Valid @RequestBody UploadDto.CreateRequest request) {
        return UploadDto.Response.from(
                sessionService.create(currentUserId(principal), request.filename(),
                        request.totalSize(), request.contentHash(), request.targetLibraryId()),
                List.of());
    }

    /** 断点续传的入口：客户端问「我传到哪儿了」，拿到已收清单后只补缺的那几片。 */
    @GetMapping("/sessions/{id}")
    UploadDto.Response get(@AuthenticationPrincipal UserDetails principal,
                           @PathVariable Long id) {
        Long userId = currentUserId(principal);
        return UploadDto.Response.from(
                sessionService.get(userId, id),
                sessionService.receivedChunks(userId, id));
    }

    /**
     * 收一个分片。
     *
     * <p><b>请求体就是分片本身</b>（{@code application/octet-stream}），
     * 不走 multipart：Boot 的 multipart 默认上限是 1MB/10MB，而且解析器会把内容
     * 先落成临时文件再交给我们，等于白写一遍磁盘。这里从
     * {@code HttpServletRequest.getInputStream()} 直接流式落盘，
     * <b>分片内容一个字节都不进内存</b>。
     *
     * <p>元信息全在 URL 里，multipart 能提供的表单字段这里根本用不上。
     */
    @PutMapping(path = "/sessions/{id}/chunks/{index}",
                consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void putChunk(@AuthenticationPrincipal UserDetails principal,
                  @PathVariable Long id,
                  @PathVariable int index,
                  HttpServletRequest request) throws IOException {
        sessionService.receiveChunk(currentUserId(principal), id, index, request.getInputStream());
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
