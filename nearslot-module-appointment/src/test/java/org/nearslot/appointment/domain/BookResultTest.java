package org.nearslot.appointment.domain;

import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import static org.junit.jupiter.api.Assertions.*;

/** 抢约返回码契约（C-CON-01：单一枚举，杜绝 0/1/2 vs 200/501 双口径）。 */
class BookResultTest {
    @Test void successOnlyWhenCode200() {
        BookResult r = new BookResult();
        r.setCode(200);
        assertTrue(r.success());
        r.setCode(50101);
        assertFalse(r.success());
        r.setCode(0);          // 旧口径 0 不得被视为成功
        assertFalse(r.success());
    }

    @Test void luaCodesMapToEnum() {
        assertEquals(BaseCode.LUA_DUPLICATE, BaseCode.of(10006));
        assertEquals(BaseCode.LUA_NO_STOCK, BaseCode.of(10005));
        assertEquals(BaseCode.LUA_NO_STOCK_KEY, BaseCode.of(10004));
        assertEquals(BaseCode.SLOT_CLOSED, BaseCode.of(50103));
        assertEquals(BaseCode.SLOT_NO_STOCK, BaseCode.of(50101));
    }

    @Test void unknownCodeFallsBackToInternalError() {
        assertEquals(BaseCode.INTERNAL_ERROR, BaseCode.of(99999));
    }
}
