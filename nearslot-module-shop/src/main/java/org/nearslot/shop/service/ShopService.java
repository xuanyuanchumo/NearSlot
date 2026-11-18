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
    /** 分页与 GEO 策略外置到 `nearslot.shop.*`。 */
    private final org.nearslot.shop.config.ShopProperties props;

    private static final String GEO_KEY = "nearslot:geo:shop";

    /**
     * GEO 半径检索脚本（返回按距离升序的 member）。
     *
     * **为什么要两套脚本（2026-11-18 运行时缺陷修复）**：
     * `GEOSEARCH` 是 Redis **6.2** 才引入的命令，本项目的验证环境是 Redis 3.0.5，
     * 直接调用会报 "Unknown Redis command called from Lua script"，
     * 表现为附近检索恒 500 —— 而单测用 mock 完全发现不了。
     *
     * 因此启动时探测服务端版本，>=6.2 用 GEOSEARCH，否则回退到长期存在的
     * `GEORADIUS ... WITHDIST ASC COUNT`（6.2 起标记废弃但 3.x~7.x 均可用）。
     * 相比"假定版本"，能力探测能让同一份产物跑在本地与生产两套 Redis 上。
     */
    private static final DefaultRedisScript<List> GEO_SEARCH = buildGeoScript(
        "local r = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2], "
      + "'BYRADIUS', ARGV[3], 'km', 'ASC', 'COUNT', ARGV[4]) "
      + "local out = {} "
      + "for i = 1, #r, 2 do out[#out + 1] = r[i] end "
      + "return out");

    /** 旧版 Redis（<6.2）回退脚本。 */
    private static final DefaultRedisScript<List> GEO_RADIUS = buildGeoScript(
        "local r = redis.call('GEORADIUS', KEYS[1], ARGV[1], ARGV[2], ARGV[3], 'km', "
      + "'WITHDIST', 'ASC', 'COUNT', ARGV[4]) "
      + "local out = {} "
      + "for i = 1, #r do out[#out + 1] = r[i][1] end "
      + "return out");

    /** 服务端是否支持 GEOSEARCH（>=6.2）；null = 尚未探测。 */
    private volatile Boolean supportsGeoSearch;

    private static DefaultRedisScript<List> buildGeoScript(String text) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptText(text);
        s.setResultType(List.class);
        return s;
    }

    /**
     * 探测服务端版本（只探测一次，之后走内存标记）。
     *
     * 探测失败按"不支持"处理（回退到 GEORADIUS，覆盖面更广），
     * 而不是抛出异常让附近检索整体不可用。
     */
    private boolean geoSearchSupported() {
        Boolean cached = supportsGeoSearch;
        if (cached != null) return cached;
        boolean supported = false;
        try {
            // RedisServerCommands#info 返回 java.util.Properties，直接取版本键，避免手解析文本
            java.util.Properties info = redis.execute(
                (org.springframework.data.redis.core.RedisCallback<java.util.Properties>) c ->
                    c.serverCommands().info("server"));
            String version = info == null ? "" : info.getProperty("redis_version", "");
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+)\\.([0-9]+)").matcher(version);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                supported = major > 6 || (major == 6 && minor >= 2);
            }
        } catch (RuntimeException e) {
            log.warn("GEO 能力探测失败，回退 GEORADIUS", e);
        }
        log.info("GEO 命令选择：{}", supported ? "GEOSEARCH(>=6.2)" : "GEORADIUS(兼容旧版)");
        supportsGeoSearch = supported;
        return supported;
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

    /**
     * 服务商分页列表（前端首页）。
     *
     * **2026-11-17 修正**：此前手写 `selectCount` + `.last("LIMIT x OFFSET y")` 两次查询，
     * 且把用户传入的 size 直接拼进 SQL。现改用 MyBatis-Plus 的 {@link Page}，
     * 由 PaginationInnerInterceptor 统一生成 count 与 limit，并受 maxLimit=500 保护。
     * 同时把 size 上限交给插件而非在这里散落 clamp 逻辑。
     */
    public org.nearslot.common.page.PageResult<Shop> page(Integer page, Integer size, Long typeId) {
        // 默认值与上限都来自 `nearslot.shop.*`：到店/上门业态的分页习惯不同，
        // 写死会导致运营想改每页条数就得发版。
        long current = page == null || page < 1 ? 1L : page;
        long pageSize = size == null || size < 1
            ? props.getDefaultPageSize()
            : Math.min(size, props.getMaxPageSize());
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Shop>lambdaQuery();
        if (typeId != null) q.eq(Shop::getTypeId, typeId);
        q.orderByAsc(Shop::getId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Shop> p =
            shopMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, pageSize), q);
        // 统一分页结构（common/page/PageResult），不再手拼 Map：
        // Map 在 OpenAPI 里是 object，前端拿不到字段类型，只能手写 any。
        return org.nearslot.common.page.PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 服务商的技师列表。 */
    public java.util.List<org.nearslot.shop.entity.Technician> technicians(Long shopId) {
        return technicianMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.nearslot.shop.entity.Technician>lambdaQuery()
                .eq(org.nearslot.shop.entity.Technician::getShopId, shopId));
    }

    /** 附近服务商：GEO 半径 + 距离升序（FR-2.3，按服务端能力自动选命令）。 */
    @SuppressWarnings("unchecked")
    public List<Shop> searchNearby(double x, double y, double kmOrNull) {
        // 半径缺省与返回条数上限外置：到店 5km 够，上门可能要 20km。
        double km = kmOrNull > 0 ? kmOrNull : props.getNearbyRadiusKm();
        DefaultRedisScript<List> script = geoSearchSupported() ? GEO_SEARCH : GEO_RADIUS;
        List<Object> members = redis.execute(script, List.of(GEO_KEY),
            String.valueOf(x), String.valueOf(y), String.valueOf(km),
            String.valueOf(props.getNearbyMaxResults()));
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
