package org.nearslot.framework.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁模板（ADR-016：优先成熟库）。
 * 基于 Redisson：可重入、看门狗自动续期、支持公平锁/读写锁，避免自研 SET NX 的续期与误删风险。
 */
@Component
public class DistributedLockTemplate {
    private final RedissonClient redisson;
    public DistributedLockTemplate(RedissonClient redisson) { this.redisson = redisson; }

    public <T> T withLock(String key, long waitMillis, long leaseMillis, Supplier<T> action) {
        RLock lock = redisson.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
            if (!acquired) return null;
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** 写锁（回滚/重建与读预热互斥，对应 hmdp-plus @ServiceLock(Write) 语义）。 */
    public <T> T withWriteLock(String key, Supplier<T> action) {
        RLock lock = redisson.getReadWriteLock(key).writeLock();
        lock.lock();
        try { return action.get(); } finally { lock.unlock(); }
    }
}
