package com.mymedia.user;

import com.mymedia.shared.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 前端的身份入口。
 *
 * <p><b>没有 login 端点，这是有意的</b>：Basic 认证下「登录」就是拿凭证请求一次
 * {@code /api/auth/me}——200 说明凭证正确，401 说明不正确。再造一个 login 端点
 * 只会多出一套与 Basic 并行的状态，而 ADR-002 选 Basic 的理由正是「少四套机制」。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final UserQueryService userQueryService;
    private final MediaTicketService mediaTicketService;

    AuthController(UserQueryService userQueryService, MediaTicketService mediaTicketService) {
        this.userQueryService = userQueryService;
        this.mediaTicketService = mediaTicketService;
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal UserDetails principal) {
        UserAccount account = current(principal);
        return new MeResponse(account.getId(), account.getUsername(),
                account.getDisplayName(), account.getRole().name());
    }

    /**
     * 签发一张短期媒体票据。
     *
     * <p>用 POST 而不是 GET：它产生一个新的凭证，不是幂等读取；而且 GET 的结果
     * 会被浏览器与中间层缓存，一张缓存住的票据过期之后前端会莫名其妙全线 401。
     */
    @PostMapping("/media-ticket")
    TicketResponse mediaTicket(@AuthenticationPrincipal UserDetails principal) {
        MediaTicketService.Issued issued =
                mediaTicketService.issue(current(principal).getId(), Instant.now());
        return new TicketResponse(issued.ticket(), issued.expiresAt());
    }

    private UserAccount current(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户"));
    }

    record MeResponse(Long userId, String username, String displayName, String role) {
    }

    record TicketResponse(String ticket, Instant expiresAt) {
    }
}
