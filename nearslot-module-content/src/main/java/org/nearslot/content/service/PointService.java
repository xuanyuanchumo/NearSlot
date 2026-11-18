package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.PointsLog;
import org.nearslot.content.entity.UserPoints;
import org.nearslot.content.mapper.PointsLogMapper;
import org.nearslot.content.mapper.UserPointsMapper;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分账户（FR-9 / ADR-029）。
 *
 * 铁律：**只改余额不记流水的一律不允许**。
 * points_log 用 uk_points_once(user_id, source, biz_id) 保证同一业务只入账一次，
 * 这样即使签到/支付回调被重复触发也不会多发积分（对应支付回调的幂等语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final UserPointsMapper pointsMapper;
    private final PointsLogMapper logMapper;

    /**
     * 增加积分（幂等）。
     *
     * @param bizId 业务唯一标识（签到日期戳 / 订单号），与 source 组成唯一键
     * @return 入账后的余额；重复入账返回当前余额（不重复加）
     */
    @Transactional(rollbackFor = Exception.class)
    public int addPoints(Long userId, int amount, String source, Long bizId, String remark) {
        if (amount <= 0) {
            throw new BaseException(BaseCode.BAD_REQUEST, "积分数量必须为正");
        }
        ensureAccount(userId);
        // 防御：ensureAccount 内部已 insert，但极端情况（并发/事务隔离）下
        // 再次读取仍可能为 null —— 不能直接解引用，否则 NPE 打断整条入账链路。
        UserPoints acc = pointsMapper.selectById(userId);
        int current = (acc == null || acc.getBalance() == null) ? 0 : acc.getBalance();
        int balanceAfter = current + amount;

        PointsLog entry = new PointsLog();
        entry.setId(IdGenerator.nextId());
        entry.setUserId(userId);
        entry.setChangeAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setSource(source);
        entry.setBizId(bizId);
        entry.setRemark(remark == null ? "" : remark);
        try {
            logMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            log.info("积分重复入账已忽略 userId={} source={} bizId={}", userId, source, bizId);
            return current;
        }
        pointsMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserPoints>()
                .eq("user_id", userId)
                .setSql("balance = balance + " + amount)
                .setSql("total_earned = total_earned + " + amount));
        return balanceAfter;
    }

    /**
     * 扣减积分（校验余额）。
     *
     * @return 扣减后余额
     */
    @Transactional(rollbackFor = Exception.class)
    public int deductPoints(Long userId, int amount, String source, Long bizId, String remark) {
        if (amount <= 0) {
            throw new BaseException(BaseCode.BAD_REQUEST, "积分数量必须为正");
        }
        ensureAccount(userId);
        UserPoints acc = pointsMapper.selectById(userId);
        int balance = (acc == null || acc.getBalance() == null) ? 0 : acc.getBalance();
        if (balance < amount) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "积分不足：当前 " + balance + "，需要 " + amount);
        }
        int balanceAfter = balance - amount;
        PointsLog entry = new PointsLog();
        entry.setId(IdGenerator.nextId());
        entry.setUserId(userId);
        entry.setChangeAmount(-amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setSource(source);
        entry.setBizId(bizId);
        entry.setRemark(remark == null ? "" : remark);
        try {
            logMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            return balance;   // 重复扣减：幂等返回
        }
        pointsMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserPoints>()
                .eq("user_id", userId)
                .setSql("balance = balance - " + amount));
        return balanceAfter;
    }

    /** 当前余额。 */
    public int balanceOf(Long userId) {
        UserPoints acc = pointsMapper.selectById(userId);
        return acc == null || acc.getBalance() == null ? 0 : acc.getBalance();
    }

    /** 积分流水（分页，可审计）。 */
    public PageResult<PointsLog> logs(Long userId, long page, long size) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<PointsLog>lambdaQuery()
            .eq(PointsLog::getUserId, userId)
            .orderByDesc(PointsLog::getId);
        var p = logMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    private void ensureAccount(Long userId) {
        if (pointsMapper.selectById(userId) != null) return;
        UserPoints acc = new UserPoints();
        acc.setUserId(userId);
        acc.setBalance(0);
        acc.setTotalEarned(0);
        try {
            pointsMapper.insert(acc);
        } catch (DuplicateKeyException ignored) {
            // 并发首次创建：已存在即可
        }
    }
}
