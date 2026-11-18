package org.nearslot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.nearslot.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探活与容量基准端点。
 *
 * 用途：SLO 规定「空接口 TPS ≥5000」，需要一个**无业务逻辑、无 DB、无 Redis**
 * 的端点来测框架层吞吐上限。用 /api/v1/shops 之类做基准会被缓存/DB 干扰，
 * 测不出 Web 层真实能力。
 *
 * 注意：此端点必须保持**绝对轻量**，只返回常量，不加任何拦截开销以外的逻辑。
 */
@Tag(name = "系统", description = "探活与容量基准")
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @Operation(summary = "空接口基准", description = "用于 TPS 压测，无任何业务逻辑")
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
