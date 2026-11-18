package org.nearslot.appointment.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.service.SlotPublishService;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 时段管理鉴权单测（FR-2.2）。
 *
 * 背景（2026-11-17 审计，Blocker）：此前该控制器**完全无鉴权**，
 * 配合拦截器把 /api/v1/slots 整体排除，匿名请求即可 POST 发布时段（已实测写库成功）。
 * 本测试锁住「角色闸 + 店铺归属闸」，防止回归。
 */
class SlotControllerTest {

    private static final long SHOP_ID = 5001L;

    private SlotPublishService slotPublishService;
    private SlotController controller;

    @BeforeEach
    void setUp() {
        slotPublishService = mock(SlotPublishService.class);
        controller = new SlotController(slotPublishService);
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    private SlotController.PublishSlotRequest request() {
        SlotController.PublishSlotRequest req = new SlotController.PublishSlotRequest();
        req.setShopId(SHOP_ID);
        req.setServiceStaffId(2001L);
        req.setStartsAt(LocalDateTime.now().plusDays(1));
        req.setEndsAt(LocalDateTime.now().plusDays(1).plusHours(1));
        req.setCapacity(3);
        return req;
    }

    @Test
    @DisplayName("publish: 未鉴权（CONSUMER）-> FORBIDDEN，不落库")
    void publishRejectsConsumer() {
        UserHolder.set(1001L);
        UserHolder.setRole("CONSUMER");

        BaseException ex = assertThrows(BaseException.class, () -> controller.publish(request()));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(slotPublishService);
    }

    @Test
    @DisplayName("publish: 无角色（匿名）-> FORBIDDEN")
    void publishRejectsAnonymous() {
        BaseException ex = assertThrows(BaseException.class, () -> controller.publish(request()));
        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(slotPublishService);
    }

    @Test
    @DisplayName("publish: MERCHANT 操作他人店铺 -> FORBIDDEN（跨租户）")
    void publishRejectsForeignShop() {
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(9999L);

        BaseException ex = assertThrows(BaseException.class, () -> controller.publish(request()));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(slotPublishService);
    }

    @Test
    @DisplayName("publish: MERCHANT 操作本店 -> 放行")
    void publishAllowsOwnShop() {
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(SHOP_ID);
        when(slotPublishService.publish(anyLong(), anyLong(), any(), any(), any(), anyInt(), any()))
            .thenReturn(3001L);

        assertEquals(3001L, controller.publish(request()).getData());
    }

    @Test
    @DisplayName("publish: PLATFORM 不受店铺归属限制 -> 放行")
    void publishAllowsPlatform() {
        UserHolder.set(1001L);
        UserHolder.setRole("PLATFORM");
        when(slotPublishService.publish(anyLong(), anyLong(), any(), any(), any(), anyInt(), any()))
            .thenReturn(3001L);

        assertEquals(3001L, controller.publish(request()).getData());
    }

    @Test
    @DisplayName("close: 先查归属再关；他人店铺被拒")
    void closeRejectsForeignShop() {
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(9999L);
        when(slotPublishService.shopIdOfSlot(3001L)).thenReturn(SHOP_ID);

        BaseException ex = assertThrows(BaseException.class, () -> controller.close(3001L));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(slotPublishService, never()).close(anyLong());
    }

    @Test
    @DisplayName("close: 本店时段 -> 关闭成功")
    void closeOwnShop() {
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(SHOP_ID);
        when(slotPublishService.shopIdOfSlot(3001L)).thenReturn(SHOP_ID);

        assertDoesNotThrow(() -> controller.close(3001L));

        verify(slotPublishService).close(3001L);
    }

    @Test
    @DisplayName("listByShop: 公开只读，无需鉴权")
    void listIsPublic() {
        when(slotPublishService.listAvailable(anyLong(), any(), any())).thenReturn(java.util.List.of());

        assertNotNull(controller.listByShop(SHOP_ID, null, null));

        verify(slotPublishService).listAvailable(eq(SHOP_ID), isNull(), isNull());
    }
}
