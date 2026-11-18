package org.nearslot.shop.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.shop.entity.ServiceItem;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.service.ShopAdminService;
import org.nearslot.shop.service.ShopService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 服务商控制器单测：验证控制器只做参数编排，鉴权与业务在 Service。 */
class ShopControllerTest {

    private ShopService shopService;
    private ShopAdminService adminService;
    private ShopController shopController;
    private ShopAdminController adminController;

    @BeforeEach
    void setUp() {
        shopService = mock(ShopService.class);
        adminService = mock(ShopAdminService.class);
        shopController = new ShopController(shopService);
        adminController = new ShopAdminController(adminService);
        UserHolder.set(1001L);
        UserHolder.setRole("PLATFORM");
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    @Test
    @DisplayName("list: 透传 page/size/typeId 到 Service，返回统一 PageResult")
    void listForwards() {
        PageResult<Shop> page = PageResult.empty(1, 10);
        when(shopService.page(1, 10, 2L)).thenReturn(page);

        assertSame(page, shopController.list(1, 10, 2L).getData());

        verify(shopService).page(1, 10, 2L);
    }

    @Test
    @DisplayName("detail: 透传 id")
    void detailForwards() {
        Shop s = new Shop();
        s.setId(1L);
        when(shopService.queryById(1L)).thenReturn(s);

        assertEquals(1L, shopController.detail(1L).getData().getId());
    }

    @Test
    @DisplayName("nearby: 透传坐标与半径")
    void nearbyForwards() {
        when(shopService.searchNearby(120.0, 30.0, 5.0)).thenReturn(List.of());

        assertNotNull(shopController.nearby(120.0, 30.0, 5.0));

        verify(shopService).searchNearby(120.0, 30.0, 5.0);
    }

    @Test
    @DisplayName("technicians: 透传 shopId")
    void techniciansForwards() {
        when(shopService.technicians(1L)).thenReturn(List.of());

        assertNotNull(shopController.technicians(1L));

        verify(shopService).technicians(1L);
    }

    @Test
    @DisplayName("admin.pageTechnicians: 透传分页参数")
    void adminPageTechnicians() {
        when(adminService.pageTechnicians(1L, 1, 2, 20)).thenReturn(PageResult.empty(2, 20));

        assertNotNull(adminController.pageTechnicians(1L, 1, 2, 20).getData());

        verify(adminService).pageTechnicians(1L, 1, 2, 20);
    }

    @Test
    @DisplayName("admin.pageServiceItems: 透传 itemType")
    void adminPageServiceItems() {
        when(adminService.pageServiceItems(1L, "SERVICE", 1, 20))
            .thenReturn(PageResult.empty(1, 20));

        assertNotNull(adminController.pageServiceItems(1L, "SERVICE", 1, 20).getData());

        verify(adminService).pageServiceItems(1L, "SERVICE", 1, 20);
    }

    @Test
    @DisplayName("admin.bindSkill: 请求体解包为 technicianId + serviceItemId + proficiency")
    void adminBindSkill() {
        ShopAdminController.BindSkillRequest req = new ShopAdminController.BindSkillRequest();
        req.setServiceItemId(7001L);
        req.setProficiency(4);
        when(adminService.bindSkill(6001L, 7001L, 4)).thenReturn(8001L);

        assertEquals(8001L, adminController.bindSkill(6001L, req).getData());

        verify(adminService).bindSkill(6001L, 7001L, 4);
    }

    @Test
    @DisplayName("admin.unbindSkill / listSkills: 透传")
    void adminSkillOps() {
        when(adminService.listSkills(6001L)).thenReturn(List.of());

        assertNotNull(adminController.listSkills(6001L));
        adminController.unbindSkill(6001L, 7001L);

        verify(adminService).listSkills(6001L);
        verify(adminService).unbindSkill(6001L, 7001L);
    }

    @Test
    @DisplayName("admin 写接口：透传到 Service（鉴权在 Service 内）")
    void adminCrudForwards() {
        Shop s = new Shop();
        s.setId(1L);
        when(adminService.createShop(any())).thenReturn(1L);

        assertEquals(1L, adminController.createShop(s).getData());
        adminController.updateShop(1L, s);
        adminController.deleteShop(1L);
        adminController.createTechnician(new Technician());
        adminController.updateTechnician(1L, new Technician());
        adminController.deleteTechnician(1L);
        adminController.createServiceItem(new ServiceItem());
        adminController.updateServiceItem(1L, new ServiceItem());
        adminController.deleteServiceItem(1L);

        verify(adminService).createShop(s);
        verify(adminService).deleteShop(1L);
        verify(adminService).deleteServiceItem(1L);
        // update 前会强制设置 id
        verify(adminService).updateShop(argThat(x -> x.getId() == 1L));
    }
}
