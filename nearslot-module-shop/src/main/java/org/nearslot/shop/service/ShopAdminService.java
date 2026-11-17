package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.context.UserHolder;
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
 * 多租户安全（P0）：
 *   1. 仅 MERCHANT / PLATFORM 角色可管理（CONSUMER/STAFF 拒绝）；
 *   2. MERCHANT 只能操作自己的 shopId（数据归属校验）；
 *   3. 新增服务商时同步布隆过滤器（否则详情被误判不存在）。
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
        requireAdmin();
        shop.setId(IdGenerator.nextId());
        shopMapper.insert(shop);
        bloomFilter.put("shop", String.valueOf(shop.getId()));
        afterChange();
        log.info("服务商已创建 shopId={}", shop.getId());
        return shop.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateShop(Shop shop) {
        requireShopOwner(shop.getId());
        requireExists(shop.getId(), shopMapper.selectById(shop.getId()));
        shopMapper.updateById(shop);
        afterChange();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteShop(Long id) {
        requireShopOwner(id);
        requireExists(id, shopMapper.selectById(id));
        shopMapper.deleteById(id);
        afterChange();
    }

    // ---------- 技师 ----------
    public Long createTechnician(Technician t) {
        requireShopOwner(t.getShopId());
        t.setId(IdGenerator.nextId());
        technicianMapper.insert(t);
        afterChange();
        return t.getId();
    }

    public void updateTechnician(Technician t) {
        var old = technicianMapper.selectById(t.getId());
        requireExists(t.getId(), old);
        requireShopOwner(old.getShopId());
        technicianMapper.updateById(t);
        afterChange();
    }

    public void deleteTechnician(Long id) {
        var old = technicianMapper.selectById(id);
        requireExists(id, old);
        requireShopOwner(old.getShopId());
        technicianMapper.deleteById(id);
        afterChange();
    }

    // ---------- 服务项 ----------
    public Long createServiceItem(ServiceItem item) {
        requireShopOwner(item.getShopId());
        item.setId(IdGenerator.nextId());
        serviceItemMapper.insert(item);
        afterChange();
        return item.getId();
    }

    public void updateServiceItem(ServiceItem item) {
        var old = serviceItemMapper.selectById(item.getId());
        requireExists(item.getId(), old);
        requireShopOwner(old.getShopId());
        serviceItemMapper.updateById(item);
        afterChange();
    }

    public void deleteServiceItem(Long id) {
        var old = serviceItemMapper.selectById(id);
        requireExists(id, old);
        requireShopOwner(old.getShopId());
        serviceItemMapper.deleteById(id);
        afterChange();
    }

    /** 角色闸：仅商家/平台可管理。 */
    private void requireAdmin() {
        String role = UserHolder.role();
        if (!"MERCHANT".equals(role) && !"PLATFORM".equals(role)) {
            throw new BaseException(BaseCode.FORBIDDEN, "无管理权限 role=" + role);
        }
    }

    /** 数据归属闸：商家只能操作自己的 shopId，平台不限。 */
    private void requireShopOwner(Long targetShopId) {
        requireAdmin();
        if ("PLATFORM".equals(UserHolder.role())) return;
        Long own = UserHolder.shopId();
        if (own == null || !own.equals(targetShopId)) {
            throw new BaseException(BaseCode.FORBIDDEN, "越权访问其他店铺数据");
        }
    }

    private void requireExists(Long id, Object entity) {
        if (id == null || entity == null) {
            throw new BaseException(BaseCode.NOT_FOUND, "记录不存在 id=" + id);
        }
    }

    private void afterChange() {
        invalidationPublisher.publish(RedisConstants.PREFIX + "shop:list");
    }
}
