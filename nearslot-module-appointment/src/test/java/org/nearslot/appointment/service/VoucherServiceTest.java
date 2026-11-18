package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.AppointmentVoucher;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.AppointmentVoucherMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 履约核销单测（FR-6.1）。
 *
 * 覆盖：签发幂等 / 状态闸 / 店铺隔离 / 重复核销 / 作废码 / 订单状态不允许核销 /
 * 归属校验（不泄露他人订单）。
 */
class VoucherServiceTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long USER_ID = 2001L;
    private static final Long SHOP_ID = 3001L;

    private AppointmentVoucherMapper voucherMapper;
    private AppointmentOrderMapper orderMapper;
    private VoucherService service;

    @BeforeEach
    void setUp() {
        voucherMapper = mock(AppointmentVoucherMapper.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        service = new VoucherService(voucherMapper, orderMapper,
            new org.nearslot.appointment.config.AppointmentProperties());
    }

    private AppointmentOrder order(int status) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(ORDER_ID);
        o.setUserId(USER_ID);
        o.setSlotId(4001L);
        o.setShopId(SHOP_ID);
        o.setStatus(status);
        return o;
    }

    private AppointmentVoucher voucher(int status) {
        AppointmentVoucher v = new AppointmentVoucher();
        v.setId(9001L);
        v.setOrderId(ORDER_ID);
        v.setUserId(USER_ID);
        v.setShopId(SHOP_ID);
        v.setCode("ABC234DEF567");
        v.setStatus(status);
        return v;
    }

    // ---------------- 签发 ----------------

    @Test
    @DisplayName("issue: 已存在核销码 -> 幂等返回原码，不重复插入")
    void issueIdempotent() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));

        String code = service.issueForOrder(order(OrderStatus.CONFIRMED.getCode()));

        assertEquals("ABC234DEF567", code);
        verify(voucherMapper, never()).insert(any(AppointmentVoucher.class));
    }

    @Test
    @DisplayName("issue: 订单非已确认 -> 不签发（返回 null）")
    void issueSkipsNonConfirmed() {
        when(voucherMapper.selectOne(any())).thenReturn(null);

        assertNull(service.issueForOrder(order(OrderStatus.PENDING.getCode())));

        verify(voucherMapper, never()).insert(any(AppointmentVoucher.class));
    }

    @Test
    @DisplayName("issue: 首次签发 -> 插入待核销码，长度 12 且不含易混字符")
    void issueCreates() {
        when(voucherMapper.selectOne(any())).thenReturn(null);
        when(voucherMapper.insert(any(AppointmentVoucher.class))).thenReturn(1);

        String code = service.issueForOrder(order(OrderStatus.CONFIRMED.getCode()));

        assertNotNull(code);
        assertEquals(12, code.length(), "核销码固定 12 位");
        assertFalse(code.matches(".*[01OIL].*"), "不得包含 0/1/O/I/L 等易混字符");
        verify(voucherMapper).insert(argThat((AppointmentVoucher v) ->
            v.getStatus() != null && v.getStatus() == AppointmentVoucher.STATUS_UNUSED
                && v.getOrderId().equals(ORDER_ID)
                && v.getShopId().equals(SHOP_ID)
                && v.getUserId().equals(USER_ID)));
    }

    // ---------------- 核销 ----------------

    @Test
    @DisplayName("redeem: 空码 -> 40001 参数错误")
    void redeemRejectsBlank() {
        BaseException ex = assertThrows(BaseException.class, () -> service.redeem("  ", 1L, SHOP_ID));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("redeem: 码不存在 -> 40401")
    void redeemUnknownCode() {
        when(voucherMapper.selectOne(any())).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("NOPE", 1L, SHOP_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("redeem: 跨店铺核销 -> 403（店铺隔离）")
    void redeemRejectsForeignShop() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("ABC234DEF567", 1L, 9999L));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(voucherMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("redeem: 平台运营 shopId=null -> 不限店铺，可核销")
    void redeemPlatformAnyShop() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));
        when(voucherMapper.update(any(), any())).thenReturn(1);
        when(orderMapper.update(any(), any())).thenReturn(1);

        assertEquals(ORDER_ID, service.redeem("ABC234DEF567", 1L, null));
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("redeem: 已核销 -> 40001，不重复改订单状态")
    void redeemAlreadyUsed() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_REDEEMED));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("ABC234DEF567", 1L, SHOP_ID));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("redeem: 已作废 -> 40001（退款后不能再核销）")
    void redeemVoided() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_VOID));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("ABC234DEF567", 1L, SHOP_ID));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("redeem: 并发竞争（CAS 影响 0 行）-> 40001，不误改订单")
    void redeemCasConflict() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));
        when(voucherMapper.update(any(), any())).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("ABC234DEF567", 1L, SHOP_ID));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("redeem: 订单已非已确认（CAS 0 行）-> 40001，且核销标记随事务回滚")
    void redeemOrderStateChanged() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));
        when(voucherMapper.update(any(), any())).thenReturn(1);
        when(orderMapper.update(any(), any())).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.redeem("ABC234DEF567", 1L, SHOP_ID));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    // ---------------- 查询 / 作废 ----------------

    @Test
    @DisplayName("findByOrder: 他人订单 -> 40401（不泄露存在性）")
    void findByOrderForeign() {
        AppointmentVoucher v = voucher(AppointmentVoucher.STATUS_UNUSED);
        v.setUserId(9999L);
        when(voucherMapper.selectOne(any())).thenReturn(v);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.findByOrder(USER_ID, ORDER_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("findByOrder: 本人订单 -> 返回核销码")
    void findByOrderOwn() {
        when(voucherMapper.selectOne(any())).thenReturn(voucher(AppointmentVoucher.STATUS_UNUSED));

        assertEquals("ABC234DEF567", service.findByOrder(USER_ID, ORDER_ID).getCode());
    }

    @Test
    @DisplayName("voidForOrder: 只作废未使用的码（已核销的不能被改回）")
    void voidOnlyUnused() {
        when(voucherMapper.update(any(), any())).thenReturn(1);

        assertDoesNotThrow(() -> service.voidForOrder(ORDER_ID));

        verify(voucherMapper).update(any(), any());
    }
}
