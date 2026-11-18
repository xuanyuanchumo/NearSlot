package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.ShopType;
import org.nearslot.content.service.ShopTypeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 店铺分类（FR-10）：公开只读 + 管理端写。 */
@Tag(name = "店铺分类", description = "分类字典（FR-10）")
@RestController
@RequestMapping("/api/v1/shop-types")
@RequiredArgsConstructor
public class ShopTypeController {

    private final ShopTypeService service;

    @Operation(summary = "分类列表", description = "公开只读，按 sort 升序")
    @GetMapping
    public Result<List<ShopType>> list() {
        return Result.ok(service.list());
    }

    @Operation(summary = "新建分类", description = "需 MERCHANT/PLATFORM")
    @PostMapping
    public Result<Long> create(@RequestBody TypeRequest req) {
        requireAdmin();
        return Result.ok(service.create(req.getName(), req.getIcon(), req.getSort()));
    }

    @Operation(summary = "更新分类")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id, @RequestBody TypeRequest req) {
        requireAdmin();
        service.update(id, req.getName(), req.getIcon(), req.getSort());
        return Result.ok(null);
    }

    @Operation(summary = "删除分类")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        requireAdmin();
        service.delete(id);
        return Result.ok(null);
    }

    private void requireAdmin() {
        String role = UserHolder.role();
        if (!"MERCHANT".equals(role) && !"PLATFORM".equals(role)) {
            throw new BaseException(BaseCode.FORBIDDEN, "无分类管理权限 role=" + role);
        }
    }

    /** 分类请求体。 */
    @Data
    public static class TypeRequest {
        private String name;
        private String icon;
        private Integer sort;
    }
}
