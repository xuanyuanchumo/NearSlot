package org.nearslot.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.service.ShopService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 服务商 API（公开只读，WebMvcConfig 已放行 /api/v1/shops/**）。 */
@Tag(name = "服务商", description = "服务商详情 / 附近检索（FR-2）")
@RestController
@RequestMapping("/api/v1/shops")
@RequiredArgsConstructor
public class ShopController {
    private final ShopService shopService;

    @Operation(summary = "服务商列表", description = "分页返回，供前端首页加载")
    @GetMapping
    public Result<org.nearslot.common.page.PageResult<Shop>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "typeId", required = false) Long typeId) {
        return Result.ok(shopService.page(page, size, typeId));
    }

    @Operation(summary = "服务商详情", description = "缓存三防（布隆防穿透 + 空值缓存 + TTL Jitter）")
    @GetMapping("/{id}")
    public Result<Shop> detail(@PathVariable("id") Long id) {
        return Result.ok(shopService.queryById(id));
    }

    @Operation(summary = "服务商技师列表", description = "按服务商查询技师")
    @GetMapping("/{id}/technicians")
    public Result<java.util.List<org.nearslot.shop.entity.Technician>> technicians(@PathVariable("id") Long id) {
        return Result.ok(shopService.technicians(id));
    }

    @Operation(summary = "附近服务商", description = "GEOSEARCH 半径检索，按距离升序返回前 20 条")
    @GetMapping("/nearby")
    public Result<List<Shop>> nearby(@RequestParam("x") double x,
                                     @RequestParam("y") double y,
                                     @RequestParam(value = "km", defaultValue = "5") double km) {
        return Result.ok(shopService.searchNearby(x, y, km));
    }
}
