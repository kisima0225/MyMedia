package com.mymedia.library;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分享链接的免登录入口：这条链接是什么、要不要密码、拿票据。
 *
 * <p>内容本身由两个领域模块各自的 share 控制器给出
 * ({@code /api/share/{token}/video/**} 与 {@code /api/share/{token}/image/**})——
 * {@code library} 不认识内容长什么样。
 */
@RestController
@RequestMapping("/api/share/{token}")
class ShareAccessController {

    private final ShareLinkService shareLinkService;

    ShareAccessController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    /** 不需要票据也能调：客户端正是靠它知道"要不要弹密码框"。 */
    @GetMapping
    ShareLinkDto.PublicView describe(@PathVariable String token) {
        ShareGrant grant = shareLinkService.resolve(token);
        return new ShareLinkDto.PublicView(
                grant.isVideo() ? LibraryDomain.VIDEO : LibraryDomain.IMAGE,
                grant.passwordProtected(),
                grant.expiresAt());
    }

    @PostMapping("/unlock")
    ShareLinkDto.UnlockResponse unlock(@PathVariable String token,
                                       @Valid @RequestBody ShareLinkDto.UnlockRequest request) {
        return shareLinkService.unlock(token, request.password())
                .map(ShareLinkDto.UnlockResponse::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码不正确"));
    }
}
