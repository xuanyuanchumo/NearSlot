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
import org.nearslot.shop.entity.TechnicianSkill;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.ServiceItemMapper;
import org.nearslot.shop.mapper.ShopMapper;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.nearslot.shop.mapper.TechnicianSkillMapper;
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
    private final TechnicianSkillMapper technicianSkillMapper;

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

    // ---------- 分页查询（管理端列表） ----------

    /**
     * 技师分页列表。
     *
     * 用 MyBatis-Plus Page 而非手拼 LIMIT：分页插件统一生成 count，
     * 且受 maxLimit 保护（客户端传 size=100000 也打不垮 DB）。
     * 可选按在岗状态过滤——运营最常问的就是"我店里还有几个在岗"。
     */
    public org.nearslot.common.page.PageResult<Technician> pageTechnicians(
            Long shopId, Integer workStatus, long page, long size) {
        requireShopOwner(shopId);
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Technician>lambdaQuery()
            .eq(Technician::getShopId, shopId);
        if (workStatus != null) q.eq(Technician::getWorkStatus, workStatus);
        q.orderByAsc(Technician::getId);
        var p = technicianMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return org.nearslot.common.page.PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 服务项分页列表（按 itemType 过滤：SERVICE 服务 / PRODUCT 商品）。 */
    public org.nearslot.common.page.PageResult<ServiceItem> pageServiceItems(
            Long shopId, String itemType, long page, long size) {
        requireShopOwner(shopId);
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<ServiceItem>lambdaQuery()
            .eq(ServiceItem::getShopId, shopId);
        if (itemType != null && !itemType.isBlank()) q.eq(ServiceItem::getItemType, itemType);
        q.orderByAsc(ServiceItem::getId);
        var p = serviceItemMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return org.nearslot.common.page.PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    // ---------- 技师-技能关联（FR-2.4 / business-domain.md §3） ----------

    /**
     * 给技师绑定技能（幂等：已存在则更新熟练度）。
     *
     * 业务必要性：没有这层关联，"用户选了肩颈推拿却被分配只会美甲的技师"。
     * 唯一键 uk_tech_skill(technician_id, service_item_id) 由 DB 兜底，
     * 这里先查后写把"重复绑定"转成"更新熟练度"，避免依赖异常处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long bindSkill(Long technicianId, Long serviceItemId, Integer proficiency) {
        Technician tech = technicianMapper.selectById(technicianId);
        requireExists(technicianId, tech);
        requireShopOwner(tech.getShopId());
        ServiceItem item = serviceItemMapper.selectById(serviceItemId);
        requireExists(serviceItemId, item);
        if (!item.getShopId().equals(tech.getShopId())) {
            // 跨店铺绑定会让"按服务项筛技师"把别家的技师筛出来
            throw new BaseException(BaseCode.BAD_REQUEST, "服务项与技师不属于同一店铺");
        }
        int prof = proficiency == null ? 3 : Math.max(1, Math.min(proficiency, 5));
        var existing = technicianSkillMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TechnicianSkill>lambdaQuery()
                .eq(TechnicianSkill::getTechnicianId, technicianId)
                .eq(TechnicianSkill::getServiceItemId, serviceItemId));
        if (existing != null) {
            existing.setProficiency(prof);
            technicianSkillMapper.updateById(existing);
            log.info("技能绑定已存在，更新熟练度 technicianId={} itemId={} prof={}",
                technicianId, serviceItemId, prof);
            return existing.getId();
        }
        TechnicianSkill skill = new TechnicianSkill();
        skill.setId(IdGenerator.nextId());
        skill.setTechnicianId(technicianId);
        skill.setServiceItemId(serviceItemId);
        skill.setProficiency(prof);
        technicianSkillMapper.insert(skill);
        log.info("技能绑定完成 technicianId={} itemId={} prof={}", technicianId, serviceItemId, prof);
        return skill.getId();
    }

    /** 解绑技能（归属校验基于技师所属店铺）。 */
    @Transactional(rollbackFor = Exception.class)
    public void unbindSkill(Long technicianId, Long serviceItemId) {
        Technician tech = technicianMapper.selectById(technicianId);
        requireExists(technicianId, tech);
        requireShopOwner(tech.getShopId());
        technicianSkillMapper.delete(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TechnicianSkill>lambdaQuery()
                .eq(TechnicianSkill::getTechnicianId, technicianId)
                .eq(TechnicianSkill::getServiceItemId, serviceItemId));
    }

    /** 查询某技师的技能（含服务项名，便于管理端直接展示）。 */
    public java.util.List<java.util.Map<String, Object>> listSkills(Long technicianId) {
        Technician tech = technicianMapper.selectById(technicianId);
        requireExists(technicianId, tech);
        requireShopOwner(tech.getShopId());
        var skills = technicianSkillMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<TechnicianSkill>lambdaQuery()
                .eq(TechnicianSkill::getTechnicianId, technicianId));
        // 批量取服务项，避免循环内查库（N+1）
        java.util.List<Long> itemIds = skills.stream().map(TechnicianSkill::getServiceItemId).toList();
        java.util.Map<Long, ServiceItem> items = itemIds.isEmpty() ? java.util.Map.of()
            : serviceItemMapper.selectBatchIds(itemIds).stream()
                .collect(java.util.stream.Collectors.toMap(ServiceItem::getId, i -> i));
        return skills.stream().map(sk -> {
            ServiceItem it = items.get(sk.getServiceItemId());
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", String.valueOf(sk.getId()));
            m.put("serviceItemId", String.valueOf(sk.getServiceItemId()));
            m.put("serviceItemName", it == null ? "" : it.getName());
            m.put("durationMin", it == null ? 0 : it.getDurationMin());
            m.put("priceCent", it == null ? 0 : it.getPriceCent());
            m.put("proficiency", sk.getProficiency());
            return m;
        }).toList();
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
