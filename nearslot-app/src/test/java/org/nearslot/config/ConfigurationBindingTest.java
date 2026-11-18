package org.nearslot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.config.AppointmentProperties;
import org.nearslot.content.config.ContentProperties;
import org.nearslot.framework.config.properties.FrameworkProperties;
import org.nearslot.framework.config.properties.StorageProperties;
import org.nearslot.shop.config.ShopProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置绑定验收（2026-11-18 去硬编码治理）。
 *
 * **为什么必须有**：常量搬进 `@ConfigurationProperties` 之后，若前缀拼错、
 * 漏 setter、或 kebab/驼峰对不上，运行时会**静默用默认值**——
 * 表现为"改了 yaml 没生效"，线上极难排查。
 *
 * 这里用 ApplicationContextRunner 起真实容器绑定一次，
 * 把"配置是否真的生效"变成可执行断言（比手搓 Binder 更贴近生产绑定路径）。
 */
class ConfigurationBindingTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @Test
    @DisplayName("content: 大V阈值/合并上限/积分规则可覆盖")
    void bindsContent() {
        runner.withPropertyValues(
                "nearslot.content.big-v-threshold=2000",
                "nearslot.content.max-big-v-merge=30",
                "nearslot.content.sign-base-points=3",
                "nearslot.content.voucher-code-alphabet=ABCDEF")
            .run(ctx -> {
                ContentProperties p = ctx.getBean(ContentProperties.class);
                assertEquals(2000, p.getBigVThreshold());
                assertEquals(30, p.getMaxBigVMerge());
                assertEquals(3, p.getSignBasePoints());
                assertEquals("ABCDEF", p.getVoucherCodeAlphabet());
            });
    }

    @Test
    @DisplayName("appointment: Hold 窗口/候补跳过/核销码长度可覆盖")
    void bindsAppointment() {
        runner.withPropertyValues(
                "nearslot.appointment.hold-minutes=15",
                "nearslot.appointment.max-promotion-skip=5",
                "nearslot.appointment.voucher-code-length=8")
            .run(ctx -> {
                AppointmentProperties p = ctx.getBean(AppointmentProperties.class);
                assertEquals(15, p.getHoldMinutes());
                assertEquals(5, p.getMaxPromotionSkip());
                assertEquals(8, p.getVoucherCodeLength());
            });
    }

    @Test
    @DisplayName("shop: 附近半径与分页上限可覆盖（到店 vs 上门业态不同）")
    void bindsShop() {
        runner.withPropertyValues(
                "nearslot.shop.nearby-radius-km=20.0",
                "nearslot.shop.max-page-size=100",
                "nearslot.shop.default-page-size=20")
            .run(ctx -> {
                ShopProperties p = ctx.getBean(ShopProperties.class);
                assertEquals(20.0, p.getNearbyRadiusKm());
                assertEquals(100, p.getMaxPageSize());
                assertEquals(20, p.getDefaultPageSize());
            });
    }

    @Test
    @DisplayName("framework: 重投次数/延迟门/缓存锁可覆盖")
    void bindsFramework() {
        runner.withPropertyValues(
                "nearslot.framework.delay-max-redeliver=5",
                "nearslot.framework.mq-message-delay-ms=30000",
                "nearslot.framework.cache-rebuild-lock-seconds=9")
            .run(ctx -> {
                FrameworkProperties p = ctx.getBean(FrameworkProperties.class);
                assertEquals(5, p.getDelayMaxRedeliver());
                assertEquals(30000L, p.getMqMessageDelayMs());
                assertEquals(9L, p.getCacheRebuildLockSeconds());
            });
    }

    @Test
    @DisplayName("storage: 后端类型/根目录/上限/白名单可覆盖")
    void bindsStorage() {
        runner.withPropertyValues(
                "nearslot.storage.type=oss",
                "nearslot.storage.local-root=/mnt/uploads",
                "nearslot.storage.max-file-size-bytes=10485760",
                "nearslot.storage.allowed-extensions=png,webp")
            .run(ctx -> {
                StorageProperties p = ctx.getBean(StorageProperties.class);
                assertEquals("oss", p.getType());
                assertEquals("/mnt/uploads", p.getLocalRoot());
                assertEquals(10485760L, p.getMaxFileSizeBytes());
                assertEquals("png,webp", p.getAllowedExtensions());
            });
    }

    @Test
    @DisplayName("mybatis: 分页上限可覆盖")
    void bindsMybatis() {
        runner.withPropertyValues("nearslot.mybatis.max-limit=1000")
            .run(ctx -> assertEquals(1000L, ctx.getBean(MybatisProperties.class).getMaxLimit()));
    }

    @Test
    @DisplayName("auth: Token TTL 与风控阈值可覆盖（被攻击时可临时收紧）")
    void bindsAuth() {
        runner.withPropertyValues(
                "nearslot.auth.access-ttl-seconds=900",
                "nearslot.auth.max-fail-count=3",
                "nearslot.auth.login-lock-ttl-seconds=600")
            .run(ctx -> {
                var p = ctx.getBean(org.nearslot.auth.config.AuthProperties.class);
                assertEquals(900L, p.getAccessTtlSeconds());
                assertEquals(3, p.getMaxFailCount());
                assertEquals(600L, p.getLoginLockTtlSeconds());
            });
    }

    @Test
    @DisplayName("agent: 确认令牌 TTL 与工具轮数可覆盖（成本与安全参数）")
    void bindsAgent() {
        runner.withPropertyValues(
                "nearslot.agent.confirm-token-ttl-seconds=120",
                "nearslot.agent.max-tool-rounds=3")
            .run(ctx -> {
                var p = ctx.getBean(org.nearslot.agent.config.AgentProperties.class);
                assertEquals(120, p.getConfirmTokenTtlSeconds());
                assertEquals(3, p.getMaxToolRounds());
            });
    }

    @Test
    @DisplayName("review: 评价时限/字数/图片张数可覆盖（不同业态差异大）")
    void bindsReview() {
        runner.withPropertyValues(
                "nearslot.review.window-days=7",
                "nearslot.review.max-images=3",
                "nearslot.review.max-content-length=200")
            .run(ctx -> {
                var p = ctx.getBean(org.nearslot.content.config.ReviewProperties.class);
                assertEquals(7, p.getWindowDays());
                assertEquals(3, p.getMaxImages());
                assertEquals(200, p.getMaxContentLength());
            });
    }

    @Test
    @DisplayName("默认值即安全值：完全不配置时各属性仍可用（避免上线漏配即崩）")
    void defaultsAreSafe() {
        runner.run(ctx -> {
            assertTrue(ctx.getBean(StorageProperties.class).getMaxFileSizeBytes() > 0);
            assertTrue(ctx.getBean(FrameworkProperties.class).getDelayMaxRedeliver() > 0);
            assertTrue(ctx.getBean(ContentProperties.class).getBigVThreshold() > 0);
            assertTrue(ctx.getBean(AppointmentProperties.class).getHoldMinutes() > 0);
            assertTrue(ctx.getBean(ShopProperties.class).getNearbyRadiusKm() > 0);
            assertTrue(ctx.getBean(MybatisProperties.class).getMaxLimit() > 0);
        });
    }

    /** 把 6 个属性类显式注册进容器（等价于生产里的组件扫描 + @ConfigurationProperties）。 */
    @Configuration
    @EnableConfigurationProperties({
        ContentProperties.class, AppointmentProperties.class, ShopProperties.class,
        FrameworkProperties.class, StorageProperties.class, MybatisProperties.class,
        org.nearslot.auth.config.AuthProperties.class,
        org.nearslot.agent.config.AgentProperties.class,
        org.nearslot.content.config.ReviewProperties.class
    })
    static class BindingConfig {
    }
}
