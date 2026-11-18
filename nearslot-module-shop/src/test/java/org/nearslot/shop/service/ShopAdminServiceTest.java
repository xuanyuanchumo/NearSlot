package org.nearslot.shop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.mq.CacheInvalidationPublisher;
import org.nearslot.shop.entity.ServiceItem;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.ServiceItemMapper;
import org.nearslot.shop.mapper.ShopMapper;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.nearslot.shop.mapper.TechnicianSkillMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 服务商管理单测（FR-2.1）。
 *
 * 覆盖两类最容易出事的语义：
 * 1. **角色/归属闸**：CONSUMER 不能写；MERCHANT 只能操作自己店；PLATFORM 不限；
 * 2. **跨租户写防护**：更新技师/服务项时，归属字段禁止通过请求体改写
 *    （2026-11-17 审计 Blocker：此前只校验旧记录归属，新 shopId 原样落库即可把记录划走）。
 */
class ShopAdminServiceTest {

    private static final Long SHOP_ID = 5001L;
    private static final Long OTHER_SHOP = 9999L;

    private ShopMapper shopMapper;
    private TechnicianMapper technicianMapper;
    private ServiceItemMapper serviceItemMapper;
    private TechnicianSkillMapper skillMapper;
    private BloomFilterHandler bloomFilter;
    private CacheInvalidationPublisher publisher;
    private CacheManager cacheManager;
    private ShopAdminService service;

    @BeforeEach
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        technicianMapper = mock(TechnicianMapper.class);
        serviceItemMapper = mock(ServiceItemMapper.class);
        skillMapper = mock(TechnicianSkillMapper.class);
        bloomFilter = mock(BloomFilterHandler.class);
        publisher = mock(CacheInvalidationPublisher.class);
        when(publisher.publish(anyString())).thenReturn(true);
        cacheManager = mock(CacheManager.class);
        service = new ShopAdminService(shopMapper, technicianMapper, serviceItemMapper,
            bloomFilter, publisher, cacheManager, skillMapper);
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    private void asPlatform() {
        UserHolder.set(1001L);
        UserHolder.setRole("PLATFORM");
    }

    private void asMerchantOf(long shopId) {
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(shopId);
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setId(SHOP_ID);
        s.setName("静心养生馆");
        return s;
    }

    // ---------------- 角色闸 ----------------

    @Test
    @DisplayName("createShop: CONSUMER -> 403，不落库")
    void createShopRejectsConsumer() {
        UserHolder.set(1001L);
        UserHolder.setRole("CONSUMER");

        BaseException ex = assertThrows(BaseException.class, () -> service.createShop(shop()));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(shopMapper, never()).insert(any(Shop.class));
    }

    @Test
    @DisplayName("createShop: 匿名（无角色）-> 403")
    void createShopRejectsAnonymous() {
        BaseException ex = assertThrows(BaseException.class, () -> service.createShop(shop()));
        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("createShop: PLATFORM -> 成功，并同步布隆（否则详情会被误判不存在）")
    void createShopWritesBloom() {
        asPlatform();

        Long id = service.createShop(shop());

        assertNotNull(id);
        verify(shopMapper).insert(any(Shop.class));
        verify(bloomFilter).put(eq("shop"), eq(String.valueOf(id)));
    }

    @Test
    @DisplayName("deleteShop: MERCHANT 删他人店铺 -> 403")
    void deleteForeignShopRejected() {
        asMerchantOf(OTHER_SHOP);

        BaseException ex = assertThrows(BaseException.class, () -> service.deleteShop(SHOP_ID));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(shopMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteShop: MERCHANT 删自己店 -> 成功")
    void deleteOwnShop() {
        asMerchantOf(SHOP_ID);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop());

        assertDoesNotThrow(() -> service.deleteShop(SHOP_ID));

        verify(shopMapper).deleteById(SHOP_ID);
    }

    @Test
    @DisplayName("updateShop: 记录不存在 -> 40401")
    void updateMissingShop() {
        asPlatform();
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.updateShop(shop()));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    // ---------------- 跨租户写防护 ----------------

    @Test
    @DisplayName("updateTechnician: 请求体带他人 shopId -> 归属字段被强制还原（跨租户写防护）")
    void updateTechnicianCannotHijackShop() {
        asPlatform();
        Technician old = new Technician();
        old.setId(6001L);
        old.setShopId(SHOP_ID);
        when(technicianMapper.selectById(6001L)).thenReturn(old);

        Technician patch = new Technician();
        patch.setId(6001L);
        patch.setShopId(OTHER_SHOP);
        patch.setName("改名");

        service.updateTechnician(patch);

        // 关键断言：落库的 shopId 必须是原归属，而不是请求体里的他人店铺
        assertEquals(SHOP_ID, patch.getShopId());
        verify(technicianMapper).updateById(patch);
    }

    @Test
    @DisplayName("updateServiceItem: 同样禁止改写归属店铺")
    void updateServiceItemCannotHijackShop() {
        asPlatform();
        ServiceItem old = new ServiceItem();
        old.setId(7001L);
        old.setShopId(SHOP_ID);
        when(serviceItemMapper.selectById(7001L)).thenReturn(old);

        ServiceItem patch = new ServiceItem();
        patch.setId(7001L);
        patch.setShopId(OTHER_SHOP);

        service.updateServiceItem(patch);

        assertEquals(SHOP_ID, patch.getShopId());
    }

    @Test
    @DisplayName("updateTechnician: MERCHANT 改他人技师 -> 403")
    void updateForeignTechnicianRejected() {
        asMerchantOf(OTHER_SHOP);
        Technician old = new Technician();
        old.setId(6001L);
        old.setShopId(SHOP_ID);
        when(technicianMapper.selectById(6001L)).thenReturn(old);

        Technician patch = new Technician();
        patch.setId(6001L);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.updateTechnician(patch));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(technicianMapper, never()).updateById(any(Technician.class));
    }

    // ---------------- 分页 ----------------

    @Test
    @DisplayName("pageTechnicians: 按店铺 + 在岗状态过滤，走 MP Page")
    void pageTechniciansFilters() {
        asPlatform();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Technician> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        p.setRecords(List.of());
        when(technicianMapper.selectPage(any(), any())).thenReturn(p);

        var res = service.pageTechnicians(SHOP_ID, 1, 1, 20);

        assertNotNull(res);
        verify(technicianMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("pageTechnicians: MERCHANT 查他人店铺 -> 403")
    void pageTechniciansRejectsForeign() {
        asMerchantOf(OTHER_SHOP);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.pageTechnicians(SHOP_ID, null, 1, 20));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("pageServiceItems: 按 itemType 过滤，走 MP Page")
    void pageServiceItemsFilters() {
        asPlatform();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ServiceItem> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        p.setRecords(List.of());
        when(serviceItemMapper.selectPage(any(), any())).thenReturn(p);

        var res = service.pageServiceItems(SHOP_ID, "SERVICE", 1, 20);

        assertNotNull(res);
        verify(serviceItemMapper).selectPage(any(), any());
    }
}
