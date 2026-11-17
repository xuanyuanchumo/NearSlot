package org.nearslot.framework.delayqueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 延迟队列单测（ADR-016：Redisson RDelayedQueue）。
 * 重点覆盖：投递单位、消费循环、<b>消费异常不阻塞后续消息</b>、中断退出与守护线程契约。
 * 消费线程通过 mock 的 take() + CountDownLatch 精确控制，不使用 sleep 轮询。
 */
class RedissonDelayQueueTest {

    private RedissonClient redisson;
    private RBlockingQueue<Object> blocking;
    private RDelayedQueue<Object> delayed;
    private RedissonDelayQueue queue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        blocking = mock(RBlockingQueue.class);
        delayed = mock(RDelayedQueue.class);
        queue = new RedissonDelayQueue(redisson);
    }

    /** 绑定队列名到 mock，并清掉 stubbing 期间记录的调用。 */
    private void stubQueue(String queueName) {
        when(redisson.getBlockingQueue(queueName)).thenReturn(blocking);
        when(redisson.getDelayedQueue(blocking)).thenReturn(delayed);
        clearInvocations(redisson, blocking, delayed);
    }

    private static Thread findThread(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (name.equals(t.getName())) return t;
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    @DisplayName("offer -> 以秒为单位投递，且延迟队列挂在对应阻塞队列上")
    void offerUsesSecondsAndBindsToBlockingQueue() {
        stubQueue("order-delay");

        queue.offer("order-delay", "payload", 30);

        verify(redisson).getBlockingQueue("order-delay");
        verify(redisson).getDelayedQueue(blocking);
        verify(delayed).offer("payload", 30L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("offer: Redis 异常原样抛出，不静默吞掉（调用方需补偿）")
    void offerPropagatesRedisFailure() {
        stubQueue("q");
        doThrow(new IllegalStateException("redis down"))
            .when(delayed).offer("p", 5L, TimeUnit.SECONDS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> queue.offer("q", "p", 5));

        assertEquals("redis down", e.getMessage());
    }

    @Test
    @DisplayName("startConsumer -> 守护线程按顺序消费并持续循环")
    void consumerProcessesAndLoops() throws Exception {
        stubQueue("q-consumer");
        LinkedBlockingQueue<Object> feed = new LinkedBlockingQueue<>();
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(2);
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        when(blocking.take()).thenAnswer(inv -> {
            Object next = feed.poll();
            if (next != null) return next;
            stop.await();
            throw new InterruptedException("测试收尾");
        });
        feed.add("a");
        feed.add("b");

        queue.startConsumer("q-consumer", p -> {
            seen.add(p);
            handled.countDown();
        });

        assertTrue(handled.await(3, TimeUnit.SECONDS), "两条消息都应被消费");
        assertEquals(List.of("a", "b"), seen, "单线程顺序消费，顺序必须与入队一致");
        Thread worker = findThread("delay-queue-q-consumer");
        assertNotNull(worker, "线程名约定为 delay-queue-<queueName>");
        assertTrue(worker.isDaemon(), "消费线程必须是守护线程，否则会阻止 JVM 退出");

        stop.countDown();
        worker.join(3000);
        assertFalse(worker.isAlive());
    }

    @Test
    @DisplayName("消费异常不阻塞：handler 抛 RuntimeException 后仍继续消费后续消息")
    void consumerSurvivesHandlerException() throws Exception {
        stubQueue("q-error");
        LinkedBlockingQueue<Object> feed = new LinkedBlockingQueue<>();
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(2);
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        when(blocking.take()).thenAnswer(inv -> {
            Object next = feed.poll();
            if (next != null) return next;
            stop.await();
            throw new InterruptedException("测试收尾");
        });
        feed.add("bad");
        feed.add("good");

        queue.startConsumer("q-error", p -> {
            seen.add(p);
            handled.countDown();
            if ("bad".equals(p)) throw new IllegalStateException("下游失败");
        });

        assertTrue(handled.await(3, TimeUnit.SECONDS), "异常消息之后的消息仍必须被消费");
        assertEquals(List.of("bad", "good"), seen, "消费异常不得中断消费循环");
        Thread worker = findThread("delay-queue-q-error");
        assertNotNull(worker);
        assertTrue(worker.isAlive(), "业务异常不应杀死消费线程（依赖业务幂等与对账补偿）");

        stop.countDown();
        worker.join(3000);
    }

    @Test
    @DisplayName("take 被中断 -> 恢复中断位并退出循环")
    void consumerStopsOnInterrupt() throws Exception {
        stubQueue("q-interrupt");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        when(blocking.take()).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            throw new InterruptedException("模拟中断");
        });
        clearInvocations(blocking);

        queue.startConsumer("q-interrupt", p -> handled.incrementAndGet());

        assertTrue(entered.await(3, TimeUnit.SECONDS), "消费线程应进入 take()");
        Thread worker = findThread("delay-queue-q-interrupt");
        assertNotNull(worker);

        release.countDown();
        worker.join(3000);

        assertFalse(worker.isAlive(), "被中断后消费线程必须退出循环");
        assertTrue(worker.isInterrupted(), "退出前必须恢复中断位（不吞中断信号）");
        assertEquals(0, handled.get());
        verify(blocking, times(1)).take();
    }
}
