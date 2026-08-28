package com.mymedia.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    MediaTicketService mediaTicketService,
                                    UserQueryService userQueryService,
                                    UserDetailsService userDetailsService) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // 分享链接：令牌本身就是凭证（bearer capability），整段免登录。
                        // 注意是单数 /api/share/**；管理端点 /api/shares（复数）
                        // 不被这个模式匹配，仍然需要登录。ShareAccessControllerTest 钉住了这件事。
                        .requestMatchers("/api/share/**").permitAll()
                        // 静态资源与前端路由整段放行：登录页本身就是静态资源，
                        // 要求登录才能拿到登录页是个死循环。真正的数据全在 /api 下，
                        // 那里仍然 anyRequest().authenticated()。
                        // favicon 两个都放行：index.html 引用的是 /favicon.svg
                        // （frontend/public/favicon.svg），/favicon.ico 是浏览器在
                        // 没有 <link rel=icon> 时的默认探测路径。浏览器取图标的请求
                        // 不带 Authorization 头，漏掉哪个，每个页面（包括匿名的登录页
                        // 与分享页）都会多打一次注定 401 的请求。
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/favicon.svg", "/favicon.ico", "/assets/**",
                                "/video/**", "/image/**", "/search", "/favorites",
                                "/tags/**", "/admin/**", "/s/**", "/login")
                        .permitAll()
                        .anyRequest().authenticated())
                // 本服务是纯 REST API，用 HTTP Basic + 无状态会话，不需要 CSRF 令牌。
                // 决策记录见 docs/adr/ADR-002-认证方案.md
                .csrf(csrf -> csrf.disable())
                // 换掉默认的 BasicAuthenticationEntryPoint：它会在 401 上带
                // WWW-Authenticate: Basic，浏览器见到就弹出原生密码框，
                // SPA 自己的登录页再也没机会出现。这里只回状态码，不回挑战头。
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 装在 Basic 之前：带了 Basic 头就以 Basic 为准，票据不覆盖更强的凭证
                .addFilterBefore(
                        new MediaTicketAuthenticationFilter(
                                mediaTicketService, userQueryService, userDetailsService),
                        BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 会给哈希加 {bcrypt} 前缀，
        // 使将来更换算法时旧密码仍可校验。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
