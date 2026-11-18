package org.nearslot.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证与风控参数（`nearslot.auth.*`）。
 *
 * **为什么外置**：Token 有效期、锁定窗口、失败阈值是**风控参数**，
 * 它们必须在被攻击时能快速收紧（例如临时把 max-fail-count 从 10 降到 3），
 * 而不是等一次发版。ADR-002 定的 30min/7d 是**基准值**，不是不可变事实。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.auth")
public class AuthProperties {

    /** 访问令牌有效期（秒），默认 30 分钟。 */
    private long accessTtlSeconds = 30 * 60L;

    /** 刷新令牌有效期（秒），默认 7 天。 */
    private long refreshTtlSeconds = 7L * 24 * 3600;

    /** 账号维度失败计数窗口（秒），默认 1 天。 */
    private long failCountTtlSeconds = 86400L;

    /** 账号维度最大失败次数，超限锁定。 */
    private int maxFailCount = 10;

    /** 账号锁定窗口（秒），默认 30 分钟。 */
    private long loginLockTtlSeconds = 1800L;

    /** IP 维度失败计数窗口（秒），默认 60 秒。 */
    private long failIpTtlSeconds = 60L;

    /** IP 维度最大失败次数（滑动窗口内）。 */
    private int maxFailIp = 5;

    /** 登录验证码有效期（秒）。 */
    private long loginCodeTtlSeconds = 300L;

    /** JWT 密钥最小字节数（安全下限，低于此值拒绝启动）。 */
    private int minSecretBytes = 32;
}
