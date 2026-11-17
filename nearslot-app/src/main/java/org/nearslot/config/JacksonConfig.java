package org.nearslot.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置（ADR-016：复用成熟库，全应用共享一个 ObjectMapper）。
 *
 * 实测踩坑：Spring Boot 4 不再自动注册 ObjectMapper Bean
 * （Boot 3 由 JacksonAutoConfiguration 提供），注入时抛
 * "required a bean of type ObjectMapper that could not be found"。
 * 此处显式注册，并统一开启 JavaTime 与「忽略未知字段」
 * （后者是 Lua 返回体字段演进时的容错关键，见 BookResult 契约修复）。
 *
 * **2026-11-17 审计修复（Major）**：契约（ADR-012 / standards/frontend.md §2）
 * 声称「Long 一律字符串化防雪花 ID 精度丢失」，但这里此前既没开
 * WRITE_NUMBERS_AS_STRINGS，也没给 Long 注册 ToStringSerializer，
 * 前端 json-bigint 拿到的是**已经丢精度的 JSON number**——保护形同虚设。
 *
 * 注意：Jackson 2.20 已移除 SerializationFeature.WRITE_NUMBERS_AS_STRINGS
 * （它原本会把**所有**数值类型都变字符串，含 int 枚举码，反而破坏契约）。
 * 正确做法是只对 Long/long 注册 ToStringSerializer——雪花 ID 正是 Long，
 * 而 int 型业务码（code/level/status）必须保持数字，前端按数字分支。
 */
@Configuration
public class JacksonConfig {

    /** 统一的本地时间格式（前后端一致，禁止各自格式化）。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    @Bean
    public ObjectMapper objectMapper() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        SimpleModule javaTime = new SimpleModule()
            .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(fmt))
            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(fmt));
        // 雪花 ID 精度保护（ADR-012）：只串化 Long，保留 int 业务码为数字
        SimpleModule longAsString = new SimpleModule()
            .addSerializer(Long.class, ToStringSerializer.instance)
            .addSerializer(Long.TYPE, ToStringSerializer.instance);
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(javaTime)
            .registerModule(longAsString)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
}
