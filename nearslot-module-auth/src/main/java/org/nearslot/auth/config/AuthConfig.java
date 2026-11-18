package org.nearslot.auth.config;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 认证配置：JWT 密钥从 .env 注入（ADR-013，禁明文）。
 *
 * **安全红线（2026-11-17 审计修复，Blocker）**：
 * 此前使用 `${JWT_SECRET:默认值}` 兜底，环境变量缺失时会用**公开的默认密钥**
 * 签发并校验令牌——任何读过源码的人都能离线伪造任意用户（含 PLATFORM 角色）的 AT。
 * 现改为「缺失即启动失败」（fail-fast）：宁可起不来，也不能带病上线。
 *
 * 生产环境应由 KMS / 密钥管理系统注入，并支持 kid 轮换。
 */
@Configuration
public class AuthConfig {

    /** JWT HMAC 密钥最小长度（HS256 安全下限 256 bit = 32 字节）。 */
    public static final int MIN_SECRET_BYTES = 32;

    @Bean
    public SecretKey jwtKey(@Value("${JWT_SECRET:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "缺少环境变量 JWT_SECRET：禁止使用内置默认密钥启动（见 security/guidelines.md N1）。"
                    + "请在 .env 中配置至少 " + MIN_SECRET_BYTES + " 字节的随机密钥。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT_SECRET 长度不足：当前 " + bytes.length + " 字节，要求 ≥ " + MIN_SECRET_BYTES + " 字节。");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
