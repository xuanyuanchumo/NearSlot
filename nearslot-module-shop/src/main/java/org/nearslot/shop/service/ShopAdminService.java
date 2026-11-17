package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.nearslot.framework.mq.CacheInvalidationPublisher;
import org.nearslot.shop.entity.ServiceItem;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.ServiceItemMapper;
import org.nearslot.shop.mapper.ShopMapper;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务商/技师/服务项管理（FR-2.1）。
 *
 * 新增服务商时必须同步布隆过滤器（put），否则新建的服务商详情会被
 * "布隆拦截"误判为不存在（research/13 踩坑：布隆空集合=全否定）。
 * 同时广播缓存失效，避免其他实例返回旧列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopAdminService {

    private final ShopMapper shopMapper;
    private final TechnicianMapper technicianMapper;
    private final ServiceItemMapper serviceItemMapper;
    private final BloomFilterHandler bloomFilter;
    private final CacheInvalidationPublisher invalidationPublisher;

    // ---------- 服务商 ----------
    @Transactional(rollbackFor = Exception.class)
    public Long createShop(Shop shop) {
        shop.setId(IdGenerator.nextId());
        shopMapper.insert(shop);
        bloomFilter.put("shop", String.valueOf(shop.getId()));  // 关键：同步布隆
        afterChange();
        log.info("服务商已创建 shopId={}", shop.getId());
        return shop.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateShop(Shop shop) {
        requireExists(shop.getId(), shopMapper.selectById(shop.getId()));
        shopMapper.updateById(shop);
        afterChange();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteShop(Long id) {
        requireExists(id, shopMapper.selectById(id));
        shopMapper.deleteById(id);
        // 布隆过滤器不支持删除：标记逻辑删除即可；误判率随删除量缓慢上升，属可接受的最终一致
        afterChange();
        log.info("服务商已删除 shopId={}", id);
    }

    // ---------- 技师 ----------
    public Long createTechnician(Technician t) {
        t.setId(IdGenerator.nextId());
        technicianMapper.insert(t);
        afterChange();
        return t.getId();
    }

    public void updateTechnician(Technician t) {
        requireExists(t.getId(), technicianMapper.selectById(t.getId()));
        technicianMapper.updateById(t);
        afterChange();
    }

    public void deleteTechnician(Long id) {
        requireExists(id, technicianMapper.selectById(id));
        technicianMapper.deleteById(id);
        afterChange();
    }

    // ---------- 服务项 ----------
    public Long createServiceItem(ServiceItem item) {
        item.setId(IdGenerator.nextId());
        serviceItemMapper.insert(item);
        afterChange();
        return item.getId();
    }

    public void updateServiceItem(ServiceItem item) {
        requireExists(item.getId(), serviceItemMapper.selectById(item.getId()));
        serviceItemMapper.updateById(item);
        afterChange();
    }

    public void deleteServiceItem(Long id) {
        requireExists(id, serviceItemMapper.selectById(id));
        serviceItemMapper.deleteById(id);
        afterChange();
    }

    private void requireExists(Long id, Object entity) {
        if (id == null || entity == null) {
            throw new BaseException(BaseCode.NOT_FOUND, "记录不存在 id=" + id);
        }
    }

    /** 变更后广播缓存失效（MQ 不可用时由 publisher 内部降级）。 */
    private void afterChange() {
        invalidationPublisher.publish(RedisConstants.PREFIX + "shop:list");
    }
}
