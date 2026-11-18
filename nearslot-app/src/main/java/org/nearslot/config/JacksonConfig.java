package org.nearslot.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置（ADR-016：复用成熟库，全应用共享一个 ObjectMapper）。
 *
 * 实测踩坑：Spring Boot 4 不再自动注册 ObjectMapper Bean，此处显式注册。
 * Long/long 用 ToStringSerializer 防雪花 ID 精度丢失（ADR-012），int 业务码保持数字。
 *
 * 评审 F-009（时区）：此前 LocalDateTime 序列化为 "yyyy-MM-dd'T'HH:mm:ss"（无时区），
 * 前端 new Date(iso) 按**浏览器本地时区**解析，再按 tzId 二次换算 → 同一时刻在
 * 三个时区显示 10:00 / 18:00 / 23:00。现在输出带 offset 的 ISO-8601，
 * new Date() 能正确解析为绝对时刻，前端再按 tzId 渲染墙钟时间。
 */
@Configuration
public class JacksonConfig {

    /** 兼容反序列化：仍接受无时区的旧格式（存量前端/脚本）。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    /** 输出格式：带 offset 的 ISO-8601（如 2026-11-18T10:00:00+08:00）。 */
    public static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 业务默认时区——**固定为 Asia/Shanghai，不用 ZoneId.systemDefault()**。
     * 实测：CI runner 是 UTC、开发机是 +08:00，用系统时区会导致同一业务时刻
     * 在不同环境输出不同的墙钟值（2026-11-19 CI 失败根因）。
     * 多时区场景由各实体的 tzId 字段 + 前端 Intl.DateTimeFormat 处理。
     */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public ObjectMapper objectMapper() {
        SimpleModule javaTime = new SimpleModule()
            .addSerializer(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeString(value.atZone(SERVER_ZONE).format(OUTPUT_FORMATTER));
                }
            })
            .addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
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
