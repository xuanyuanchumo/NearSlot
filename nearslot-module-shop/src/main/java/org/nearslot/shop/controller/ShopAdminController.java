package org.nearslot.shop.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.shop.entity.ServiceItem;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.service.ShopAdminService;
import org.nearslot.common.page.PageResult;
import org.springframework.web.bind.annotation.*;

/**
 * 服务商/技师/服务项管理 API（FR-2.1，管理端）。
 *
 * 安全说明：当前仅依赖登录拦截，未做角色鉴权（项目尚无 role 体系）。
 * 生产必须补管理员角色校验，否则任何登录用户都可修改数据。
 */
@Tag(name = "服务商管理", description = "服务商/技师/服务项 CRUD（FR-2.1）")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ShopAdminController {

    private final ShopAdminService adminService;

    // ---------- 服务商 ----------
    @Operation(summary = "创建服务商")
    @PostMapping("/shops")
    public Result<Long> createShop(@RequestBody Shop shop) {
        return Result.ok(adminService.createShop(shop));
    }

    @Operation(summary = "更新服务商")
    @PutMapping("/shops/{id}")
    public Result<Void> updateShop(@PathVariable("id") Long id, @RequestBody Shop shop) {
        shop.setId(id);
        adminService.updateShop(shop);
        return Result.ok(null);
    }

    @Operation(summary = "删除服务商")
    @DeleteMapping("/shops/{id}")
    public Result<Void> deleteShop(@PathVariable("id") Long id) {
        adminService.deleteShop(id);
        return Result.ok(null);
    }

    // ---------- 技师 ----------
    @Operation(summary = "创建技师")
    @PostMapping("/technicians")
    public Result<Long> createTechnician(@RequestBody Technician t) {
        return Result.ok(adminService.createTechnician(t));
    }

    @Operation(summary = "更新技师")
    @PutMapping("/technicians/{id}")
    public Result<Void> updateTechnician(@PathVariable("id") Long id, @RequestBody Technician t) {
        t.setId(id);
        adminService.updateTechnician(t);
        return Result.ok(null);
    }

    @Operation(summary = "删除技师")
    @DeleteMapping("/technicians/{id}")
    public Result<Void> deleteTechnician(@PathVariable("id") Long id) {
        adminService.deleteTechnician(id);
        return Result.ok(null);
    }

    // ---------- 分页列表 ----------

    @Operation(summary = "技师分页列表", description = "管理端；可选按在岗状态过滤，需店铺归属")
    @GetMapping("/technicians")
    public Result<PageResult<Technician>> pageTechnicians(
            @RequestParam("shopId") Long shopId,
            @RequestParam(value = "workStatus", required = false) Integer workStatus,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(adminService.pageTechnicians(shopId, workStatus, page, size));
    }

    @Operation(summary = "服务项分页列表", description = "管理端；可选按 itemType(SERVICE/PRODUCT) 过滤")
    @GetMapping("/service-items")
    public Result<PageResult<ServiceItem>> pageServiceItems(
            @RequestParam("shopId") Long shopId,
            @RequestParam(value = "itemType", required = false) String itemType,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(adminService.pageServiceItems(shopId, itemType, page, size));
    }

    // ---------- 技师-技能关联（FR-2.4） ----------

    @Operation(summary = "技师技能列表", description = "返回该技师可提供的服务项（含名称/时长/价格/熟练度）")
    @GetMapping("/technicians/{id}/skills")
    public Result<java.util.List<java.util.Map<String, Object>>> listSkills(@PathVariable("id") Long id) {
        return Result.ok(adminService.listSkills(id));
    }

    @Operation(summary = "绑定技师技能", description = "幂等：已存在则更新熟练度；服务项与技师必须同店")
    @PostMapping("/technicians/{id}/skills")
    public Result<Long> bindSkill(@PathVariable("id") Long id,
                                  @RequestBody BindSkillRequest req) {
        return Result.ok(adminService.bindSkill(id, req.getServiceItemId(), req.getProficiency()));
    }

    @Operation(summary = "解绑技师技能")
    @DeleteMapping("/technicians/{id}/skills/{serviceItemId}")
    public Result<Void> unbindSkill(@PathVariable("id") Long id,
                                    @PathVariable("serviceItemId") Long serviceItemId) {
        adminService.unbindSkill(id, serviceItemId);
        return Result.ok(null);
    }

    /** 绑定技能请求体。 */
    @io.swagger.v3.oas.annotations.media.Schema(description = "绑定技师技能请求")
    @lombok.Data
    public static class BindSkillRequest {
        @jakarta.validation.constraints.NotNull(message = "serviceItemId 不能为空")
        private Long serviceItemId;
        /** 熟练度 1-5，缺省 3。 */
        private Integer proficiency;
    }

    // ---------- 服务项 ----------
    @Operation(summary = "创建服务项")
    @PostMapping("/service-items")
    public Result<Long> createServiceItem(@RequestBody ServiceItem item) {
        return Result.ok(adminService.createServiceItem(item));
    }

    @Operation(summary = "更新服务项")
    @PutMapping("/service-items/{id}")
    public Result<Void> updateServiceItem(@PathVariable("id") Long id, @RequestBody ServiceItem item) {
        item.setId(id);
        adminService.updateServiceItem(item);
        return Result.ok(null);
    }

    @Operation(summary = "删除服务项")
    @DeleteMapping("/service-items/{id}")
    public Result<Void> deleteServiceItem(@PathVariable("id") Long id) {
        adminService.deleteServiceItem(id);
        return Result.ok(null);
    }
}
