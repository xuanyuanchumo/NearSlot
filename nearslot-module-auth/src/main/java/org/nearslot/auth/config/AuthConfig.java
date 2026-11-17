package org.nearslot.auth.config;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/** 认证配置：JWT 密钥从 .env 注入（ADR-013，禁明文）。 */
@Configuration
public class AuthConfig {
    @Bean
    public SecretKey jwtKey(@Value("${JWT_SECRET:nearslot-dev-secret-key-please-change-me-32b}") String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
