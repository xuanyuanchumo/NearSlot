package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.PointsLog;
import org.nearslot.content.entity.UserPoints;
import org.nearslot.content.mapper.PointsLogMapper;
import org.nearslot.content.mapper.UserPointsMapper;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 积分账户单测（FR-9 / ADR-029）。
 *
 * 铁律：只改余额不记流水的一律不允许；
 * 唯一键 (user_id, source, biz_id) 保证同一业务只入账一次。
 */
class PointServiceTest {

    private UserPointsMapper pointsMapper;
    private PointsLogMapper logMapper;
    private PointService service;

    @BeforeEach
    void setUp() {
        pointsMapper = mock(UserPointsMapper.class);
        logMapper = mock(PointsLogMapper.class);
        service = new PointService(pointsMapper, logMapper);
    }

    private UserPoints account(int balance) {
        UserPoints p = new UserPoints();
        p.setUserId(1001L);
        p.setBalance(balance);
        p.setTotalEarned(balance);
        return p;
    }

    @Test
    @DisplayName("addPoints: 正常入账 -> 写流水 + 加余额")
    void addPointsWorks() {
        when(pointsMapper.selectById(1001L)).thenReturn(account(10));

        int bal = service.addPoints(1001L, 5, "SIGN", 20261117L, "签到");

        assertEquals(15, bal);
        verify(logMapper).insert(argThat((PointsLog l) ->
            l.getChangeAmount() == 5 && l.getBalanceAfter() == 15 && "SIGN".equals(l.getSource())));
        verify(pointsMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("addPoints: 重复入账（唯一键冲突）-> 幂等返回当前余额，不重复加")
    void addPointsIdempotent() {
        when(pointsMapper.selectById(1001L)).thenReturn(account(10));
        when(logMapper.insert(any(PointsLog.class)))
            .thenThrow(new DuplicateKeyException("uk_points_once"));

        int bal = service.addPoints(1001L, 5, "SIGN", 20261117L, "签到");

        assertEquals(10, bal, "重复入账不得改变余额");
        verify(pointsMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("addPoints: 数量非正 -> 40001")
    void addPointsRejectsNonPositive() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.addPoints(1001L, 0, "SIGN", 1L, ""));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("deductPoints: 余额不足 -> 40001，不写流水")
    void deductInsufficient() {
        when(pointsMapper.selectById(1001L)).thenReturn(account(3));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.deductPoints(1001L, 10, "ORDER", 1L, "抵扣"));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        verify(logMapper, never()).insert(any(PointsLog.class));
    }

    @Test
    @DisplayName("deductPoints: 余额充足 -> 扣减并记录负流水")
    void deductWorks() {
        when(pointsMapper.selectById(1001L)).thenReturn(account(20));

        int bal = service.deductPoints(1001L, 5, "ORDER", 9001L, "抵扣");

        assertEquals(15, bal);
        verify(logMapper).insert(argThat((PointsLog l) -> l.getChangeAmount() == -5));
    }

    @Test
    @DisplayName("deductPoints: 重复扣减（唯一键冲突）-> 幂等返回原余额")
    void deductIdempotent() {
        when(pointsMapper.selectById(1001L)).thenReturn(account(20));
        when(logMapper.insert(any(PointsLog.class)))
            .thenThrow(new DuplicateKeyException("uk_points_once"));

        int bal = service.deductPoints(1001L, 5, "ORDER", 9001L, "抵扣");

        assertEquals(20, bal, "重复扣减不得改变余额");
        verify(pointsMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("deductPoints: 数量非正 -> 40001")
    void deductRejectsNonPositive() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.deductPoints(1001L, -1, "ORDER", 1L, ""));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("addPoints: 首次入账自动创建账户")
    void addCreatesAccount() {
        when(pointsMapper.selectById(1001L)).thenReturn(null).thenReturn(null);

        service.addPoints(1001L, 5, "SIGN", 1L, "签到");

        verify(pointsMapper).insert(any(UserPoints.class));
    }

    @Test
    @DisplayName("logs: 分页查询走 MP Page")
    void logsPaged() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<PointsLog> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        p.setRecords(java.util.List.of());
        when(logMapper.selectPage(any(), any())).thenReturn(p);

        assertNotNull(service.logs(1001L, 1, 20));
        verify(logMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("balanceOf: 无账户 -> 0")
    void balanceMissing() {
        when(pointsMapper.selectById(1001L)).thenReturn(null);
        assertEquals(0, service.balanceOf(1001L));
    }
}
