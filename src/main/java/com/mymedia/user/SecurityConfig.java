package com.mymedia.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // 分享链接：令牌本身就是凭证（bearer capability），整段免登录。
                        // 注意是单数 /api/share/**；管理端点 /api/shares（复数）
                        // 不被这个模式匹配，仍然需要登录。ShareAccessControllerTest 钉住了这件事。
                        .requestMatchers("/api/share/**").permitAll()
                        .anyRequest().authenticated())
                // 本服务是纯 REST API，用 HTTP Basic + 无状态会话，不需要 CSRF 令牌。
                // 决策记录见 docs/adr/ADR-002-认证方案.md
                .csrf(csrf -> csrf.disable())
                // 换掉默认的 BasicAuthenticationEntryPoint：它会在 401 上带
                // WWW-Authenticate: Basic，浏览器见到就弹出原生密码框，
                // SPA 自己的登录页再也没机会出现。这里只回状态码，不回挑战头。
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 会给哈希加 {bcrypt} 前缀，
        // 使将来更换算法时旧密码仍可校验。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
