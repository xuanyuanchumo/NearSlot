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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 服务商查询单测（FR-2.1/2.3）。
 *
 * 重点锁住 2026-11-17 修正的两点：
 * 1. 布隆过滤器**不再作为业务正确性判定**：未命中仍要回源 DB，
 *    否则多实例部署时 A 实例新建的店在 B 实例恒 404；
 * 2. 分页改走 MyBatis-Plus Page，不再手拼 LIMIT。
 */
class ShopServiceTest {

    private static final Long SHOP_ID = 5001L;

    private ShopMapper shopMapper;
    private CacheManager cacheManager;
    private BloomFilterHandler bloomFilter;
    private ShopService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        bloomFilter = mock(BloomFilterHandler.class);
        cacheManager = mock(CacheManager.class);
        service = new ShopService(shopMapper, cacheManager, bloomFilter,
            mock(org.springframework.data.redis.core.StringRedisTemplate.class),
            mock(TechnicianMapper.class),
            new org.nearslot.shop.config.ShopProperties());
    }

    private Shop shop(long id) {
        Shop s = new Shop();
        s.setId(id);
        s.setName("静心养生馆");
        s.setTypeId(1L);
        return s;
    }

    @Test
    @DisplayName("queryById: 缓存命中 -> 返回实体")
    @SuppressWarnings("unchecked")
    void queryByIdCacheHit() {
        when(cacheManager.get(anyString(), any(), any(Function.class), anyLong()))
            .thenReturn(shop(SHOP_ID));

        assertEquals(SHOP_ID, service.queryById(SHOP_ID).getId());
    }

    @Test
    @DisplayName("queryById: 布隆未命中但 DB 有 -> 仍返回（布隆不得决定业务正确性）")
    @SuppressWarnings("unchecked")
    void queryByIdBloomMissStillReturns() {
        when(bloomFilter.mightContain("shop", String.valueOf(SHOP_ID))).thenReturn(false);
        when(cacheManager.get(anyString(), any(), any(Function.class), anyLong()))
            .thenReturn(shop(SHOP_ID));

        // 关键回归：B 实例没预热到该店时，不能误判 404
        assertEquals(SHOP_ID, service.queryById(SHOP_ID).getId());
        // 命中真实数据后应回写布隆（增量自愈）
        verify(bloomFilter).put("shop", String.valueOf(SHOP_ID));
    }

    @Test
    @DisplayName("queryById: DB 也没有 -> 40401")
    @SuppressWarnings("unchecked")
    void queryByIdNotFound() {
        when(bloomFilter.mightContain(anyString(), anyString())).thenReturn(false);
        when(cacheManager.get(anyString(), any(), any(Function.class), anyLong()))
            .thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.queryById(SHOP_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("page: 走 MP Page，返回统一 PageResult 结构")
    void pageUsesMpPagination() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Shop> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        p.setRecords(List.of(shop(1L), shop(2L)));
        p.setTotal(2);
        when(shopMapper.selectPage(any(), any())).thenReturn(p);

        var res = service.page(1, 10, null);

        assertEquals(2, res.items().size());
        assertEquals(2, res.total());
        assertEquals(1, res.page());
        // 关键：必须走 selectPage（由分页插件生成 count+limit），不能手拼 SQL
        verify(shopMapper).selectPage(any(), any());
        verify(shopMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("page: size 超上限被钳制到 50（防大页拖垮 DB）")
    void pageClampsSize() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Shop> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 50);
        p.setRecords(List.of());
        when(shopMapper.selectPage(any(), any())).thenReturn(p);

        service.page(1, 100000, null);   // 超出上限 -> 被 ShopProperties.maxPageSize 钳制

        verify(shopMapper).selectPage(argThat(page ->
            ((com.baomidou.mybatisplus.extension.plugins.pagination.Page<?>) page).getSize() == 50),
            any());
    }
}
