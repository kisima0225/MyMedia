package com.mymedia.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 会给哈希加 {bcrypt} 前缀，
        // 使将来更换算法时旧密码仍可校验。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
