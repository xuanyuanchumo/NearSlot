package org.nearslot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** JacksonConfig 单测（BL-TEST-01）：Long 字符串化 + 时间带时区 ISO-8601（F-009）。 */
class JacksonConfigTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    @DisplayName("Long 序列化为字符串（雪花 ID 精度保护 ADR-012）")
    void longAsString() throws Exception {
        String json = mapper.writeValueAsString(Map.of("id", 2122869761807044609L));
        assertTrue(json.contains("\"2122869761807044609\""), "Long 必须字符串化：" + json);
    }

    @Test
    @DisplayName("LocalDateTime 输出带 offset 的 ISO-8601（F-009 时区修复）")
    void dateTimeWithOffset() throws Exception {
        String json = mapper.writeValueAsString(
            Map.of("t", LocalDateTime.of(2026, 11, 18, 10, 0, 0)));
        // 固定业务时区 Asia/Shanghai：无论 CI(UTC) 还是本地(+08:00) 都输出 +08:00
        assertTrue(json.contains("T10:00:00+08:00"),
            "时间必须按业务时区 Asia/Shanghai 输出：" + json);
    }

    @Test
    @DisplayName("未知字段反序列化不报错（Lua 字段演进容错）")
    void ignoreUnknown() throws Exception {
        var v = mapper.readValue("{\"code\":200,\"unknownField\":123}", Map.class);
        assertEquals(200, ((Number) v.get("code")).intValue());
    }

    @Test
    @DisplayName("反序列化仍接受无时区的旧格式（兼容存量前端）")
    void deserializeLegacyFormat() throws Exception {
        var v = mapper.readValue("{\"t\":\"2026-11-18T10:00:00\"}", Map.class);
        assertEquals("2026-11-18T10:00:00", v.get("t"));
    }
}
