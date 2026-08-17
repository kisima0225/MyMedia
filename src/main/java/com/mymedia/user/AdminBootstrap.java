package com.mymedia.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /**
     * 首次启动时创建初始管理员。已存在则跳过，因此重启是安全的。
     */
    @Bean
    ApplicationRunner createInitialAdmin(
            UserRegistrationService registrationService,
            UserAccountRepository repository,
            @Value("${mymedia.admin.username:admin}") String username,
            @Value("${mymedia.admin.password:admin}") String password) {

        return args -> {
            if (repository.existsByUsername(username)) {
                return;
            }
            registrationService.register(username, password, UserRole.ADMIN);
            log.warn("已创建初始管理员账号 '{}'，请立即修改默认密码", username);
        };
    }
}
