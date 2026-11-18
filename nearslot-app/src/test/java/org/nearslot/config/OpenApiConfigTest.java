package org.nearslot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OpenApiConfig 单测（BL-TEST-01）：文档元信息与 bearerAuth 安全方案。 */
class OpenApiConfigTest {

    @Test
    @DisplayName("openApi bean 包含标题/版本/bearerAuth 方案")
    void openApiMetadata() {
        var api = new OpenApiConfig().nearSlotOpenApi();
        assertNotNull(api);
        assertNotNull(api.getInfo());
        assertEquals("v1", api.getInfo().getVersion());
        assertTrue(api.getInfo().getTitle().contains("NearSlot"));
        assertNotNull(api.getComponents());
        assertNotNull(api.getComponents().getSecuritySchemes());
        assertTrue(api.getComponents().getSecuritySchemes().containsKey("bearerAuth"));
    }
}
