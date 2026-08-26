package com.mymedia.user;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 把 URL 上的 {@code ?ticket=} 换成一个标准的 {@code SecurityContext}。
 *
 * <p>装在 {@code BasicAuthenticationFilter} <b>之前</b>，因此下游的
 * {@code @AuthenticationPrincipal}、{@code LibraryAccessService.canAccess}
 * 与那条「404 而非 403」的规矩全部照常工作——它们根本不知道票据存在。
 *
 * <p><b>白名单是本类的全部意义。</b>票据只在三条只读媒体路径上被接受：
 * 视频流、图片页、派生资源。理由与限制写在 {@link MediaTicketService} 的类注释
 * 与 ADR-008 里。往这个列表里加路径之前先想清楚：那条路径被一个泄漏在
 * 浏览器历史里的 URL 调用，最坏会发生什么。
 */
class MediaTicketAuthenticationFilter extends OncePerRequestFilter {

    /** 只在这三条路径上接受票据。**加之前先读类注释。** */
    private static final List<String> ALLOWED_PATTERNS = List.of(
            "/api/video/stream/**",
            "/api/image/page/**",
            "/api/assets/**");

    private static final String TICKET_PARAM = "ticket";

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final MediaTicketService mediaTicketService;
    private final UserQueryService userQueryService;
    private final UserDetailsService userDetailsService;

    MediaTicketAuthenticationFilter(MediaTicketService mediaTicketService,
                                    UserQueryService userQueryService,
                                    UserDetailsService userDetailsService) {
        this.mediaTicketService = mediaTicketService;
        this.userQueryService = userQueryService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * 不在白名单上、没带票据、或者已经认证过的请求一律直接放行给下一环。
     *
     * <p>「已经认证过就跳过」很重要：带了 Basic 头又顺手带了票据时，
     * 应当以 Basic 的身份为准，票据不能覆盖一个更强的凭证。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getParameter(TICKET_PARAM) == null) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (!context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        String candidate = path;
        return ALLOWED_PATTERNS.stream().noneMatch(pattern -> matcher.match(pattern, candidate));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticationFor(request.getParameter(TICKET_PARAM))
                    .ifPresent(authentication -> {
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);
                    });
        }
        chain.doFilter(request, response);
    }

    /** 票据无效、用户已删除、账号已停用——三种情况都安静地返回空，交给下游回 401。 */
    private Optional<Authentication> authenticationFor(String ticket) {
        Optional<Long> userId = mediaTicketService.resolve(ticket, Instant.now());
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        try {
            UserAccount account = userQueryService.getById(userId.get());
            UserDetails details = userDetailsService.loadUserByUsername(account.getUsername());
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                    details, null, details.getAuthorities()));
        } catch (RuntimeException e) {
            // getById 对已删除用户抛 NotFoundException，loadUserByUsername 对停用账号
            // 抛 UsernameNotFoundException。票据合法但主体没了，等同于没带票据。
            return Optional.empty();
        }
    }
}
