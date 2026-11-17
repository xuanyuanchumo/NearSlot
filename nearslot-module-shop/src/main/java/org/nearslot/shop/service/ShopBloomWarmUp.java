package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.mapper.ShopMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 布隆过滤器预热（FR-2.1）。
 *
 * **实测踩坑（严重）**：布隆过滤器初始化时是空集合，
 * 若不把已有 shop id 灌进去，所有查询都会 mightContain=false，
 * 导致**所有服务商详情 404**（"服务商不存在（布隆拦截）"）。
 * 布隆过滤器只能回答"可能存在/一定不存在"，
 * 未预热时它对一切回答"一定不存在"。
 *
 * 生产应按增量更新（新增 shop 时 put），此处启动全量预热 + 提供增量入口。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ShopBloomWarmUp {

    private final ShopMapper shopMapper;
    private final BloomFilterHandler bloomFilter;

    @Bean
    public ApplicationRunner shopBloomWarmer() {
        return args -> {
            try {
                List<Shop> shops = shopMapper.selectList(null);
                shops.forEach(s -> bloomFilter.put("shop", String.valueOf(s.getId())));
                log.info("布隆过滤器预热完成：{} 个服务商已灌入", shops.size());
            } catch (RuntimeException e) {
                log.warn("布隆预热失败（不影响启动，但详情接口可能误判不存在）: {}", e.toString());
            }
        };
    }
}
