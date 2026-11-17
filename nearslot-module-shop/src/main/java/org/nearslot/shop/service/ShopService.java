package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.mapper.ShopMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 服务商查询（FR-2.1/2.3）。
 * 缓存三防：BloomFilter 防穿透 + CacheManager 空值缓存 + TTL Jitter 防雪崩。
 * GEO 用 Lua 包装 GEOSEARCH，规避 spring-data-redis 版本间 API 差异（ADR-016）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {
    private final ShopMapper shopMapper;
    private final CacheManager cacheManager;
    private final BloomFilterHandler bloomFilter;
    private final StringRedisTemplate redis;
    private final org.nearslot.shop.mapper.TechnicianMapper technicianMapper;

    private static final String GEO_KEY = "nearslot:geo:shop";

    /** GEOSEARCH 包装：返回按距离升序的 member 列表。 */
    private static final DefaultRedisScript<List> GEO_SEARCH = buildGeoScript();

    private static DefaultRedisScript<List> buildGeoScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptText(
            "local r = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2], "
          + "'BYRADIUS', ARGV[3], 'km', 'ASC', 'COUNT', ARGV[4]) "
          + "local out = {} "
          + "for i = 1, #r, 2 do out[#out + 1] = r[i] end "
          + "return out");
        s.setResultType(List.class);
        return s;
    }

    /**
     * 按 ID 查（三防）。
     *
     * **2026-11-17 审计修复（Major）**：布隆过滤器是**进程内** Guava 结构，
     * 多实例部署时 A 实例新建的 shop 只写进 A 的过滤器，
     * B 实例 mightContain=false 就抛 404——用户在不同实例间"店铺时有时无"，
     * 只能等重启全量预热才恢复。
     *
     * 修正原则：布隆过滤器只用于**挡住必然不存在**的请求以省一次 DB 查询，
     * 它绝不能成为业务正确性的判定依据。这里改为「布隆未命中仍然回源 DB」，
     * 命中则走缓存；DB 也没有时才返回 404（并把空值缓存交给 CacheManager 的 TTL_NULL）。
     * 代价是穿透时多一次 DB 查询——与"误杀真实数据"相比，这个代价可以接受。
     */
    public Shop queryById(Long id) {
        Shop cached = cacheManager.get(RedisConstants.CACHE_SHOP + id, Shop.class,
            k -> shopMapper.selectById(id), 30 * 60);
        if (cached == null) {
            throw new BaseException(BaseCode.NOT_FOUND, "服务商不存在");
        }
        // 命中真实数据后顺手补写布隆（增量自愈，减少后续穿透概率）
        if (!bloomFilter.mightContain("shop", String.valueOf(id))) {
            bloomFilter.put("shop", String.valueOf(id));
        }
        return cached;
    }

    /** 服务商分页列表（前端首页）。 */
    public java.util.Map<String, Object> page(int page, int size, Long typeId) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Shop>lambdaQuery();
        if (typeId != null) q.eq(Shop::getTypeId, typeId);
        long total = shopMapper.selectCount(q);
        q.last("LIMIT " + Math.max(1, Math.min(size, 50)) + " OFFSET " + Math.max(0, (page - 1) * size));
        java.util.List<Shop> items = shopMapper.selectList(q);
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("items", items);
        res.put("total", total);
        res.put("page", page);
        res.put("size", size);
        return res;
    }

    /** 服务商的技师列表。 */
    public java.util.List<org.nearslot.shop.entity.Technician> technicians(Long shopId) {
        return technicianMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.nearslot.shop.entity.Technician>lambdaQuery()
                .eq(org.nearslot.shop.entity.Technician::getShopId, shopId));
    }

    /** 附近服务商：GEOSEARCH 半径 + 距离升序（FR-2.3）。 */
    @SuppressWarnings("unchecked")
    public List<Shop> searchNearby(double x, double y, double km) {
        List<Object> members = redis.execute(GEO_SEARCH, List.of(GEO_KEY),
            String.valueOf(x), String.valueOf(y), String.valueOf(km), "20");
        if (members == null || members.isEmpty()) return List.of();
        List<Shop> shops = new ArrayList<>();
        for (Object m : members) {
            try {
                Shop s = shopMapper.selectById(Long.valueOf(String.valueOf(m)));
                if (s != null) shops.add(s);
            } catch (NumberFormatException ignored) {
                // 忽略异常 member
            }
        }
        return shops.stream().filter(Objects::nonNull).toList();
    }

    /** 发布时段时同步 GEO（幂等）。 */
    public void addGeo(Long shopId, double x, double y) {
        redis.opsForGeo().add(GEO_KEY,
            new org.springframework.data.geo.Point(x, y), String.valueOf(shopId));
    }
}
