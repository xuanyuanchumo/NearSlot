package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.AppointmentVoucher;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.AppointmentVoucherMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 履约核销（FR-6.1 / business-domain.md §4 的 P0 缺口）。
 *
 * **为什么必须有**：支付成功后订单停在 CONFIRMED 就再无下一步——
 * 商家无法确认服务已完成，用户也无法证明履约。核销码是"预付 + 到店/上门"
 * 模式的履约凭证（business-scope-research.md §105）。
 *
 * 设计要点：
 * 1. **一单一码**：uk_voucher_order 兜底，重复签发走幂等返回；
 * 2. **码值不可枚举**：12 位 base32 随机串（不含易混字符），不放用户 ID；
 * 3. **核销 = 状态机迁移**：CONFIRMED → DONE，保留审计而非删记录；
 * 4. **店铺隔离**：核销必须校验 voucher.shopId 与操作人归属店铺一致；
 * 5. **幂等**：重复核销同一码返回业务错误而非 500，且不重复改订单状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    /**
     * 随机源：SecureRandom 而非 Math.random ——
     * 核销码是履约凭证，可预测的伪随机等于把核销权交给攻击者。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppointmentVoucherMapper voucherMapper;
    private final AppointmentOrderMapper orderMapper;
    /** 核销码策略（长度/字母表/重试）外置到 `nearslot.appointment.*`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /**
     * 支付成功后签发核销码（幂等）。
     *
     * @return 核销码；订单状态不允许签发时返回 null（如已取消/已退款）
     */
    @Transactional(rollbackFor = Exception.class)
    public String issueForOrder(AppointmentOrder order) {
        // 幂等：一单一码，已存在直接返回原码（支付回调可能重投）
        AppointmentVoucher existing = voucherMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentVoucher>()
                .eq("order_id", order.getId()));
        if (existing != null) return existing.getCode();

        if (order.getStatus() == null || order.getStatus() != OrderStatus.CONFIRMED.getCode()) {
            log.warn("订单状态非已确认，跳过核销码签发 orderId={} status={}",
                order.getId(), order.getStatus());
            return null;
        }

        for (int i = 0; i < props.getVoucherIssueRetry(); i++) {
            AppointmentVoucher v = new AppointmentVoucher();
            v.setId(IdGenerator.nextId());
            v.setOrderId(order.getId());
            v.setSlotId(order.getSlotId());
            v.setShopId(order.getShopId());
            v.setUserId(order.getUserId());
            v.setCode(randomCode());
            v.setStatus(AppointmentVoucher.STATUS_UNUSED);
            try {
                voucherMapper.insert(v);
                log.info("核销码已签发 orderId={} shopId={}", order.getId(), order.getShopId());
                return v.getCode();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 码碰撞或并发重复签发：重新读一次，若已有则复用
                AppointmentVoucher again = voucherMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentVoucher>()
                        .eq("order_id", order.getId()));
                if (again != null) return again.getCode();
                log.warn("核销码生成冲突，重试第 {} 次 orderId={}", i + 1, order.getId());
            }
        }
        throw new BaseException(BaseCode.INTERNAL_ERROR, "核销码签发失败，请稍后重试");
    }

    /**
     * 核销（商家侧）。
     *
     * @param code        用户出示的核销码
     * @param operatorId  操作人（店员）
     * @param shopId      操作人归属店铺；null 表示平台（不限店铺）
     * @return 核销后的订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long redeem(String code, Long operatorId, Long shopId) {
        if (code == null || code.isBlank()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "核销码不能为空");
        }
        AppointmentVoucher v = voucherMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentVoucher>()
                .eq("code", code.trim().toUpperCase()));
        if (v == null) {
            throw new BaseException(BaseCode.NOT_FOUND, "核销码不存在");
        }
        // 店铺隔离：店员只能核销自己店的码（平台运营 shopId 为 null 时不限）
        if (shopId != null && !shopId.equals(v.getShopId())) {
            throw new BaseException(BaseCode.FORBIDDEN, "该核销码不属于当前店铺");
        }
        if (v.getStatus() == AppointmentVoucher.STATUS_REDEEMED) {
            throw new BaseException(BaseCode.BAD_REQUEST, "该核销码已被使用（" + v.getRedeemedAt() + "）");
        }
        if (v.getStatus() == AppointmentVoucher.STATUS_VOID) {
            throw new BaseException(BaseCode.BAD_REQUEST, "该核销码已作废");
        }

        // 状态 CAS：并发核销同一码时只有一个成功（用条件更新，不依赖先查后写）
        // 用字符串列名的 UpdateWrapper（与 AppointmentService#casStatus 同款）：
        // lambdaUpdate 依赖 MyBatis-Plus 的实体内省缓存（TableInfo），
        // 在无 SqlSessionFactory 的纯单测环境会抛 "can not find lambda cache"。
        // 字符串列名同样是类型安全的常量，且不引入测试必须起容器的负担。
        int updated = voucherMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentVoucher>()
                .eq("id", v.getId())
                .eq("status", AppointmentVoucher.STATUS_UNUSED)
                .set("status", AppointmentVoucher.STATUS_REDEEMED)
                .set("redeemed_by", operatorId)
                .set("redeemed_at", LocalDateTime.now()));
        if (updated == 0) {
            throw new BaseException(BaseCode.BAD_REQUEST, "该核销码已被使用，请勿重复核销");
        }

        // 订单 CONFIRMED → DONE（同样用 CAS，避免与取消并发互相覆盖）
        int moved = orderMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentOrder>()
                .eq("id", v.getOrderId())
                .eq("status", OrderStatus.CONFIRMED.getCode())
                .set("status", OrderStatus.DONE.getCode()));
        if (moved == 0) {
            // 订单已不是"已确认"（可能被取消/已核销过）：回滚本次核销标记，保持两边一致
            throw new BaseException(BaseCode.BAD_REQUEST, "订单当前状态不可核销（可能已取消或已完成）");
        }
        log.info("核销成功 orderId={} shopId={} operator={}", v.getOrderId(), v.getShopId(), operatorId);
        return v.getOrderId();
    }

    /** 作废核销码（订单取消/退款时调用，防止已退款还能核销）。 */
    @Transactional(rollbackFor = Exception.class)
    public void voidForOrder(Long orderId) {
        voucherMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentVoucher>()
                .eq("order_id", orderId)
                .eq("status", AppointmentVoucher.STATUS_UNUSED)
                .set("status", AppointmentVoucher.STATUS_VOID));
    }

    /** 用户查看自己的核销码（按订单）。 */
    public AppointmentVoucher findByOrder(Long userId, Long orderId) {
        AppointmentVoucher v = voucherMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentVoucher>()
                .eq("order_id", orderId));
        if (v == null || !v.getUserId().equals(userId)) {
            // 不泄露他人订单是否存在
            throw new BaseException(BaseCode.NOT_FOUND);
        }
        return v;
    }

    /** 商家待核销列表（按店铺隔离，用 MP Page 分页）。 */
    public org.nearslot.common.page.PageResult<AppointmentVoucher> pageByShop(
            Long shopId, Integer status, long page, long size) {
        var q = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentVoucher>()
            .eq("shop_id", shopId);
        if (status != null) q.eq("status", status);
        q.orderByDesc("id");
        var p = voucherMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return org.nearslot.common.page.PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 生成 12 位随机核销码。 */
    private String randomCode() {
        char[] alphabet = props.getVoucherCodeAlphabet().toCharArray();
        if (alphabet.length < 2) {
            throw new IllegalStateException(
                "nearslot.appointment.voucher-code-alphabet 至少需要 2 个字符");
        }
        StringBuilder sb = new StringBuilder(props.getVoucherCodeLength());
        for (int i = 0; i < props.getVoucherCodeLength(); i++) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }
}
