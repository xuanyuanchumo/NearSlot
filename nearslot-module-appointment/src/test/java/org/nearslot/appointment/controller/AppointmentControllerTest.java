package org.nearslot.appointment.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.AppointmentVoucher;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.appointment.service.PaymentService;
import org.nearslot.appointment.service.VoucherService;
import org.nearslot.appointment.service.WaitlistService;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 预约/支付/核销控制器单测（参数编排 + 角色闸）。 */
class AppointmentControllerTest {

    private AppointmentService appointmentService;
    private WaitlistService waitlistService;
    private PaymentService paymentService;
    private VoucherService voucherService;

    @BeforeEach
    void setUp() {
        appointmentService = mock(AppointmentService.class);
        waitlistService = mock(WaitlistService.class);
        paymentService = mock(PaymentService.class);
        voucherService = mock(VoucherService.class);
        UserHolder.set(1001L);
        UserHolder.setRole("CONSUMER");
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    @Test
    @DisplayName("预约：抢约/详情/列表/取消/候补全部透传当前用户")
    void appointmentForwardsCurrentUser() {
        AppointmentController c = new AppointmentController(appointmentService, waitlistService);
        org.nearslot.appointment.dto.BookRequest req = new org.nearslot.appointment.dto.BookRequest();
        req.setSlotId(3001L);
        when(appointmentService.book(1001L, 3001L)).thenReturn(9L);

        assertEquals(9L, c.book(req).getData());
        c.detail(9L);
        c.myOrders(null);
        c.cancel(9L);
        assertEquals(1L, c.joinWaitlist(3001L).getData() == null ? 1L : 1L);
        c.leaveWaitlist(3001L);

        verify(appointmentService).book(1001L, 3001L);
        verify(appointmentService).queryOrder(1001L, 9L);
        verify(appointmentService).listMyOrders(1001L, null);
        verify(appointmentService).cancel(1001L, 9L);
        verify(waitlistService).join(3001L, 1001L);
        verify(waitlistService).leave(3001L, 1001L);
    }

    @Test
    @DisplayName("支付回调：返回结果枚举名")
    void paymentCallback() {
        PaymentController c = new PaymentController(paymentService);
        when(paymentService.onPaid(9L, "PAY-1")).thenReturn(PaymentService.PayResult.CONFIRMED);
        PaymentController.PayCallbackRequest req = new PaymentController.PayCallbackRequest();
        req.setOrderId(9L);
        req.setPayNo("PAY-1");

        assertEquals("CONFIRMED", c.callback(req).getData());
    }

    @Test
    @DisplayName("核销：CONSUMER 调用 -> 403（核销是商家侧操作）")
    void redeemRejectsConsumer() {
        VoucherController c = new VoucherController(voucherService);
        VoucherController.RedeemRequest req = new VoucherController.RedeemRequest();
        req.setCode("ABC");

        BaseException ex = assertThrows(BaseException.class, () -> c.redeem(req));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(voucherService);
    }

    @Test
    @DisplayName("核销：MERCHANT 用自己店铺 shopId 核销")
    void redeemMerchant() {
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(5001L);
        VoucherController c = new VoucherController(voucherService);
        VoucherController.RedeemRequest req = new VoucherController.RedeemRequest();
        req.setCode("ABC");
        when(voucherService.redeem("ABC", 1001L, 5001L)).thenReturn(9L);

        assertEquals(9L, c.redeem(req).getData());
    }

    @Test
    @DisplayName("核销：PLATFORM shopId 传 null（不限店铺）")
    void redeemPlatform() {
        UserHolder.setRole("PLATFORM");
        VoucherController c = new VoucherController(voucherService);
        VoucherController.RedeemRequest req = new VoucherController.RedeemRequest();
        req.setCode("ABC");
        when(voucherService.redeem("ABC", 1001L, null)).thenReturn(9L);

        assertEquals(9L, c.redeem(req).getData());
    }

    @Test
    @DisplayName("待核销列表：平台未绑定店铺 -> 返回空页（不越权看全平台）")
    void voucherPagePlatformReturnsEmpty() {
        UserHolder.setRole("PLATFORM");
        VoucherController c = new VoucherController(voucherService);

        PageResult<AppointmentVoucher> res = c.page(null, 1, 20).getData();

        assertEquals(0, res.items().size());
        verifyNoInteractions(voucherService);
    }

    @Test
    @DisplayName("待核销列表：MERCHANT 按自己店查询")
    void voucherPageMerchant() {
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(5001L);
        VoucherController c = new VoucherController(voucherService);
        when(voucherService.pageByShop(5001L, null, 1, 20)).thenReturn(PageResult.empty(1, 20));

        assertNotNull(c.page(null, 1, 20).getData());

        verify(voucherService).pageByShop(5001L, null, 1, 20);
    }

    @Test
    @DisplayName("按订单取核销码：透传当前用户做归属校验")
    void voucherByOrder() {
        VoucherController c = new VoucherController(voucherService);
        AppointmentVoucher v = new AppointmentVoucher();
        v.setCode("ABC");
        when(voucherService.findByOrder(1001L, 9L)).thenReturn(v);

        assertEquals("ABC", c.byOrder(9L).getData().getCode());
    }
}
