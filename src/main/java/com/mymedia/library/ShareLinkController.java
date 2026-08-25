package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享链接的管理端点：列出我创建的、撤销其中一条。
 *
 * <p>只认 {@code created_by} 与 {@code id}，不需要知道目标是视频还是图片，
 * 所以它可以住在 {@code library}。创建端点做不到这一点（要校验目标的访问权），
 * 因此分别住在两个领域模块里。
 *
 * <p><b>路径是 {@code /api/shares}（复数），免登录访问用的是
 * {@code /api/share/{token}}（单数）。</b>两者不会互相匹配——
 * Spring 的路径模式按整段比较，{@code /api/share/**} 不匹配 {@code /api/shares}。
 * Task 9 有一个测试专门钉住这件事。
 */
@RestController
@RequestMapping("/api/shares")
class ShareLinkController {

    private final ShareLinkService shareLinkService;
    private final UserQueryService userQueryService;

    ShareLinkController(ShareLinkService shareLinkService, UserQueryService userQueryService) {
        this.shareLinkService = shareLinkService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<ShareLinkDto.Response> list(@AuthenticationPrincipal UserDetails principal) {
        return shareLinkService.listCreatedBy(currentUserId(principal)).stream()
                .map(ShareLinkDto.Response::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        shareLinkService.revoke(currentUserId(principal), id);
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
