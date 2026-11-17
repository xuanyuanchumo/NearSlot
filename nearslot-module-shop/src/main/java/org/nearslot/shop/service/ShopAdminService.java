package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 服务商/技师/服务项管理（FR-2.1）。
 *
 * 多租户安全（P0）：
 *   1. 仅 MERCHANT / PLATFORM 角色可管理（CONSUMER/STAFF 拒绝）；
 *   2. MERCHANT 只能操作自己的 shopId（数据归属校验）；
 *   3. 新增服务商时同步布隆过滤器（否则详情被误判不存在）。
 *
 * **2026-11-17 审计修复（Blocker + Major×2）**：
 *   · 跨租户写：updateTechnician/updateServiceItem 只校验旧记录归属，
 *     请求体的新 shopId 会原样落库 -> MERCHANT 可把记录划到他人店铺。
 *     现强制归属字段不可通过更新接口修改。
 *   · 缓存失效 key 错位：广播 nearslot:shop:list，而实际缓存的是
 *     nearslot:cache:shop:{id} -> 改/删店铺后详情缓存永不失效（L2 30min / L1 20min）。
 *   · 广播时机：在事务提交前发出且忽略 publish 失败，其他实例可能读到旧值 / 无人兜底。
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
    private final CacheManager cacheManager;

    // ---------- 服务商 ----------
    @Transactional(rollbackFor = Exception.class)
    public Long createShop(Shop shop) {
        requireAdmin();
        shop.setId(IdGenerator.nextId());
        shopMapper.insert(shop);
        bloomFilter.put("shop", String.valueOf(shop.getId()));
        afterChange(shop.getId());
        log.info("服务商已创建 shopId={}", shop.getId());
        return shop.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateShop(Shop shop) {
        requireShopOwner(shop.getId());
        requireExists(shop.getId(), shopMapper.selectById(shop.getId()));
        shopMapper.updateById(shop);
        afterChange(shop.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteShop(Long id) {
        requireShopOwner(id);
        requireExists(id, shopMapper.selectById(id));
        shopMapper.deleteById(id);
        afterChange(id);
    }

    // ---------- 技师 ----------
    @Transactional(rollbackFor = Exception.class)
    public Long createTechnician(Technician t) {
        requireShopOwner(t.getShopId());
        t.setId(IdGenerator.nextId());
        technicianMapper.insert(t);
        afterChange(t.getShopId());
        return t.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTechnician(Technician t) {
        var old = technicianMapper.selectById(t.getId());
        requireExists(t.getId(), old);
        requireShopOwner(old.getShopId());
        // 归属字段禁止通过更新接口修改（跨租户写防护）
        t.setShopId(old.getShopId());
        technicianMapper.updateById(t);
        afterChange(old.getShopId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTechnician(Long id) {
        var old = technicianMapper.selectById(id);
        requireExists(id, old);
        requireShopOwner(old.getShopId());
        technicianMapper.deleteById(id);
        afterChange(old.getShopId());
    }

    // ---------- 服务项 ----------
    @Transactional(rollbackFor = Exception.class)
    public Long createServiceItem(ServiceItem item) {
        requireShopOwner(item.getShopId());
        item.setId(IdGenerator.nextId());
        serviceItemMapper.insert(item);
        afterChange(item.getShopId());
        return item.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateServiceItem(ServiceItem item) {
        var old = serviceItemMapper.selectById(item.getId());
        requireExists(item.getId(), old);
        requireShopOwner(old.getShopId());
        // 同 updateTechnician：归属字段禁止通过更新接口改写
        item.setShopId(old.getShopId());
        serviceItemMapper.updateById(item);
        afterChange(old.getShopId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteServiceItem(Long id) {
        var old = serviceItemMapper.selectById(id);
        requireExists(id, old);
        requireShopOwner(old.getShopId());
        serviceItemMapper.deleteById(id);
        afterChange(old.getShopId());
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

    /**
     * 变更后失效「该店铺详情」缓存（key 必须与 ShopService#queryById 一致）。
     * 事务中延迟到提交后执行：否则其他实例可能在提交前就回源读到旧值。
     */
    private void afterChange(Long shopId) {
        if (shopId == null) return;
        String key = RedisConstants.CACHE_SHOP + shopId;
        Runnable task = () -> {
            if (!invalidationPublisher.publish(key)) {
                // MQ 不可用：至少保证本实例正确（其他实例依赖 TTL 最终一致）
                cacheManager.invalidate(key);
                log.warn("缓存失效广播不可用，已降级为本地失效 key={}", key);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
