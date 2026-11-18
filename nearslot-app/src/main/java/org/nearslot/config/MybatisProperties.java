package org.nearslot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 分页插件限流参数（`nearslot.mybatis.*`）。
 *
 * 单页上限是**防打穿 DB 的保护阈值**：客户端传 size=100000 时必须被插件拦住，
 * 而不是靠每个 Service 记得 clamp。上限值应随 DB 规格调整，故外置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.mybatis")
public class MybatisProperties {

    /** 单页最大条数（超出被 PaginationInnerInterceptor 钳制）。 */
    private long maxLimit = 500L;

    /** 是否允许分页溢出回到首页（false = 超出总页数返回空页）。 */
    private boolean overflow = false;
}
