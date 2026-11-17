package org.nearslot.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 密码编码器（security/guidelines.md §2：BCrypt 加盐）。 */
@Configuration
public class PasswordConfig {

    /** 统一强度 10（默认），便于后续集中调参。 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
