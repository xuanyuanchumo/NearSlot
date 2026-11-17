package org.nearslot.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置（ADR-016：复用成熟库，全应用共享一个 ObjectMapper）。
 *
 * 实测踩坑：Spring Boot 4 不再自动注册 ObjectMapper Bean
 * （Boot 3 由 JacksonAutoConfiguration 提供），注入时抛
 * "required a bean of type ObjectMapper that could not be found"。
 * 此处显式注册，并统一开启 JavaTime 与「忽略未知字段」
 * （后者是 Lua 返回体字段演进时的容错关键，见 BookResult 契约修复）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
