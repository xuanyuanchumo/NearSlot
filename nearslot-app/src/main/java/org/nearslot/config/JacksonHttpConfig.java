package org.nearslot.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * HTTP 响应体序列化配置（**Jackson 3**）。
 *
 * **关键事实（2026-11-17 实测确认）**：Spring Boot 4 已迁移到 Jackson 3
 * （tools.jackson.*），HTTP 消息转换器用的是 tools.jackson.databind.json.JsonMapper，
 * **不再读取**我们为 Jackson 2（com.fasterxml.jackson.*）注册的 ObjectMapper Bean。
 * 这正是此前「Long 字符串化」配置形同虚设的原因：
 * 实测 GET /appointments 返回的 id 仍是 JSON number，
 * 浏览器 JSON.parse 后雪花 ID 直接丢精度（ADR-012 的保护从未生效）。
 *
 * 因此这里改用 Boot 4 的官方扩展点 JsonMapperBuilderCustomizer 注册 Long → String 序列化。
 * 只串化 Long（雪花 ID 是 Long），**保留 int 业务码为数字**——
 * 前端 code/level/status 按数字分支，全部变字符串反而会破坏契约。
 *
 * 说明：应用内部仍保留 Jackson 2 的 ObjectMapper Bean（见 JacksonConfig），
 * 供缓存序列化与 Lua 返回体解析使用；两条链路各自独立，互不影响。
 */
@Configuration
public class JacksonHttpConfig {

    @Bean
    public JsonMapperBuilderCustomizer nearslotLongAsStringCustomizer() {
        return builder -> {
            SimpleModule longAsString = new SimpleModule("nearslot-long-as-string");
            longAsString.addSerializer(Long.class, ToStringSerializer.instance);
            longAsString.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.addModule(longAsString);
        };
    }
}
