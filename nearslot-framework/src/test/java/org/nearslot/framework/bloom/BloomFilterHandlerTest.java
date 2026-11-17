package org.nearslot.framework.bloom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 布隆过滤器单测（FR-2.1 防穿透；含本轮实测踩坑的预热校验）。 */
class BloomFilterHandlerTest {

    @Test
    @DisplayName("未初始化 -> mightContain 返回 false（防 NPE）")
    void uninitializedReturnsFalse() {
        BloomFilterHandler h = new BloomFilterHandler();
        assertFalse(h.mightContain("shop", "1"));
    }

    @Test
    @DisplayName("已放入的值 -> mightContain 为 true")
    void putThenContains() {
        BloomFilterHandler h = new BloomFilterHandler();
        h.init("shop", 1000, 0.01);
        h.put("shop", "1");
        h.put("shop", "2");
        assertTrue(h.mightContain("shop", "1"));
        assertTrue(h.mightContain("shop", "2"));
    }

    @Test
    @DisplayName("关键回归：未预热时对已有数据的判断为 false（曾导致详情全 404）")
    void notWarmedUpRejectsEverything() {
        BloomFilterHandler h = new BloomFilterHandler();
        h.init("shop", 1000, 0.01);
        // 未 put 任何值 —— 布隆对一切回答"一定不存在"
        assertFalse(h.mightContain("shop", "1"),
            "空布隆会否定一切，这正是 ShopBloomWarmUp 存在的原因");
    }

    @Test
    @DisplayName("未初始化的 name -> put 不抛异常（幂等安全）")
    void putUnknownNameIsNoop() {
        BloomFilterHandler h = new BloomFilterHandler();
        assertDoesNotThrow(() -> h.put("nonexistent", "x"));
    }
}
