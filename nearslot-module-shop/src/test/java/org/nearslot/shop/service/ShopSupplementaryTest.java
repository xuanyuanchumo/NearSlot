package org.nearslot.shop.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.shop.api.ShopRecommendQueryImpl;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.mapper.ShopMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 服务商启动预热与契约实现单测（FR-2.1/2.3）。 */
class ShopSupplementaryTest {

    @Test
    @DisplayName("ShopBloomWarmUp: 启动把所有 shopId 灌入布隆")
    void bloomWarmUpLoadsAll() throws Exception {
        ShopMapper mapper = mock(ShopMapper.class);
        BloomFilterHandler bloom = mock(BloomFilterHandler.class);
        Shop s1 = new Shop();
        s1.setId(1L);
        Shop s2 = new Shop();
        s2.setId(2L);
        when(mapper.selectList(isNull())).thenReturn(List.of(s1, s2));

        new ShopBloomWarmUp(mapper, bloom).shopBloomWarmer().run(null);

        verify(bloom).put("shop", "1");
        verify(bloom).put("shop", "2");
    }

    @Test
    @DisplayName("ShopBloomWarmUp: DB 异常不影响启动（只告警）")
    void bloomWarmUpSwallowsException() throws Exception {
        ShopMapper mapper = mock(ShopMapper.class);
        BloomFilterHandler bloom = mock(BloomFilterHandler.class);
        when(mapper.selectList(isNull())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> new ShopBloomWarmUp(mapper, bloom).shopBloomWarmer().run(null));
    }

    @Test
    @DisplayName("ShopRecommendQueryImpl: 分页结果转 ShopView（含经纬度）")
    void recommendQueryMapsView() {
        ShopService shopService = mock(ShopService.class);
        Shop s = new Shop();
        s.setId(1L);
        s.setName("静心养生馆");
        s.setAddress("文三路");
        s.setX(120.15);
        s.setY(30.28);
        s.setTypeId(1L);
        when(shopService.page(1, 10, 1L)).thenReturn(
            org.nearslot.common.page.PageResult.ofPage(List.of(s), 1, 1, 10, 1));

        var views = new ShopRecommendQueryImpl(shopService).page(1, 10, 1L);

        assertEquals(1, views.size());
        assertEquals("静心养生馆", views.get(0).name());
        assertEquals(120.15, views.get(0).x());
    }

    @Test
    @DisplayName("ShopRecommendQueryImpl: nearby 委托 GEO 检索并转视图")
    void recommendNearby() {
        ShopService shopService = mock(ShopService.class);
        Shop s = new Shop();
        s.setId(2L);
        s.setName("店B");
        when(shopService.searchNearby(120.0, 30.0, 5.0)).thenReturn(List.of(s));

        var views = new ShopRecommendQueryImpl(shopService).nearby(120.0, 30.0, 5.0);

        assertEquals(1, views.size());
        assertEquals("店B", views.get(0).name());
    }

    @Test
    @DisplayName("ShopService: technicians 按店铺查（走 MP 查询）")
    void techniciansByShop() {
        var mapper = mock(org.nearslot.shop.mapper.TechnicianMapper.class);
        var shopMapper = mock(ShopMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        ShopService svc = new ShopService(shopMapper,
            mock(org.nearslot.framework.cache.CacheManager.class),
            mock(BloomFilterHandler.class),
            mock(org.springframework.data.redis.core.StringRedisTemplate.class), mapper,
            new org.nearslot.shop.config.ShopProperties());

        assertTrue(svc.technicians(1L).isEmpty());
        verify(mapper).selectList(any());
    }
}
