package org.nearslot.framework.lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分布式锁模板单测（ADR-016：Redisson 成熟库）。
 * 重点覆盖：获取失败、线程中断、租约过期（isHeldByCurrentThread=false）、业务异常仍释放锁。
 * 全部使用 Mockito 模拟 RedissonClient，不连接真实 Redis。
 */
class DistributedLockTemplateTest {

    private RedissonClient redisson;
    private RLock lock;
    private DistributedLockTemplate template;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        template = new DistributedLockTemplate(redisson);
        // 清掉 stubbing 过程中记录的那次调用，使后续 verify 计数只统计真实调用
        clearInvocations(redisson, lock);
    }

    @AfterEach
    void tearDown() {
        // 防止"中断"用例把中断位残留给同线程的后续用例
        Thread.interrupted();
    }

    @Test
    @DisplayName("获取锁成功 -> 执行业务、返回结果，并在 finally 中释放锁")
    void acquireSuccessRunsActionAndUnlocks() throws Exception {
        when(lock.tryLock(500L, 1000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        clearInvocations(redisson, lock);

        String result = template.withLock("order:1", 500, 1000, () -> "ok");

        assertEquals("ok", result);
        verify(redisson).getLock("order:1");
        verify(lock).tryLock(500L, 1000L, TimeUnit.MILLISECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("获取锁失败（超时）-> 返回 null，不执行业务，也不尝试解锁")
    void notAcquiredReturnsNull() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        clearInvocations(redisson, lock);

        AtomicBoolean ran = new AtomicBoolean(false);
        Object result = template.withLock("k", 1, 2, () -> {
            ran.set(true);
            return "x";
        });

        assertNull(result, "抢锁失败必须返回 null 而不是抛异常，调用方据此降级");
        assertFalse(ran.get(), "抢锁失败绝不能执行临界区代码");
        verify(lock, never()).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("业务抛异常 -> 异常原样透传，且锁仍被释放（不泄漏锁）")
    void actionFailureStillUnlocks() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        clearInvocations(redisson, lock);

        IllegalStateException boom = new IllegalStateException("业务炸了");
        Supplier<Object> action = () -> {
            throw boom;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> template.withLock("k", 1, 2, action));

        assertSame(boom, thrown);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("业务返回 null 且持锁 -> 返回 null 并释放锁")
    void nullResultStillUnlocks() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        clearInvocations(redisson, lock);

        Supplier<Object> action = () -> null;

        assertNull(template.withLock("k", 1, 2, action));
        verify(lock).unlock();
    }

    @Test
    @DisplayName("线程被中断 -> 返回 null、恢复中断位且不解锁")
    void interruptedReturnsNullAndKeepsFlag() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
            .thenThrow(new InterruptedException("中断"));
        clearInvocations(redisson, lock);

        AtomicBoolean ran = new AtomicBoolean(false);
        Object result = template.withLock("k", 1, 2, () -> {
            ran.set(true);
            return "x";
        });

        assertNull(result);
        assertFalse(ran.get());
        assertTrue(Thread.currentThread().isInterrupted(),
            "捕获 InterruptedException 后必须恢复中断位，否则上层无法感知取消");
        verify(lock, never()).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("租约已过期（isHeldByCurrentThread=false）-> 不调用 unlock，避免误删他人锁")
    void leaseExpiredSkipsUnlock() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        clearInvocations(redisson, lock);

        assertEquals("ok", template.withLock("k", 1, 2, () -> "ok"));

        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("withWriteLock -> 加写锁、执行业务、释放锁")
    void writeLockHappyPath() {
        RReadWriteLock rw = mock(RReadWriteLock.class);
        RLock writeLock = mock(RLock.class);
        when(redisson.getReadWriteLock("shop:1")).thenReturn(rw);
        when(rw.writeLock()).thenReturn(writeLock);
        clearInvocations(redisson, rw, writeLock);

        String result = template.withWriteLock("shop:1", () -> "rebuilt");

        assertEquals("rebuilt", result);
        verify(redisson).getReadWriteLock("shop:1");
        verify(writeLock).lock();
        verify(writeLock).unlock();
    }

    @Test
    @DisplayName("withWriteLock 业务异常 -> 仍释放写锁并透传异常")
    void writeLockReleasesOnFailure() {
        RReadWriteLock rw = mock(RReadWriteLock.class);
        RLock writeLock = mock(RLock.class);
        when(redisson.getReadWriteLock("shop:1")).thenReturn(rw);
        when(rw.writeLock()).thenReturn(writeLock);
        clearInvocations(redisson, rw, writeLock);

        Supplier<Object> action = () -> {
            throw new IllegalArgumentException("回滚失败");
        };

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> template.withWriteLock("shop:1", action));

        assertEquals("回滚失败", thrown.getMessage());
        verify(writeLock).unlock();
    }
}
