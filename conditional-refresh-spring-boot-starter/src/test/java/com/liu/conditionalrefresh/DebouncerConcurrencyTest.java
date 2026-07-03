package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.listener.Debouncer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debouncer 并发行为测试 — 验证多线程池设计允许不同 key 并行执行。
 *
 * <h3>测试覆盖</h3>
 * <ul>
 *     <li>不同 key 的刷新任务可并行执行（无单线程瓶颈）</li>
 *     <li>同一 key 仍严格去抖</li>
 *     <li>自定义线程池大小 = 1 时退化为串行</li>
 * </ul>
 */
class DebouncerConcurrencyTest {

    private Debouncer debouncer;

    @BeforeEach
    void setUp() {
        // 使用 4 线程池，便于观察并行行为
        debouncer = new Debouncer(100, TimeUnit.MILLISECONDS, 4);
    }

    @AfterEach
    void tearDown() {
        debouncer.close();
    }

    @Test
    @DisplayName("不同 key 刷新可并行执行（不会被单线程调度器串行化）")
    void debounce_differentKeys_parallelExecution() throws InterruptedException {
        // 使用 CountDownLatch 来同步等待所有任务完成
        CountDownLatch holdLatch = new CountDownLatch(1);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        Runnable concurrentTask = () -> {
            int current = concurrentCount.incrementAndGet();
            // 更新最大并发数
            maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
            try {
                holdLatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentCount.decrementAndGet();
            }
        };

        // 提交 3 个不同 key 的任务（都被 hold 住）
        debouncer.debounce("key1", concurrentTask);
        debouncer.debounce("key2", concurrentTask);
        debouncer.debounce("key3", concurrentTask);

        // 等待所有任务都开始执行（超过去抖窗口 100ms 后）
        Thread.sleep(200);

        // 至少有 2 个任务同时执行（证明了并行能力）
        assertTrue(maxConcurrent.get() >= 2,
                "Multiple tasks should execute in parallel. Max concurrent: " + maxConcurrent.get());

        // 释放所有任务
        holdLatch.countDown();
    }

    @Test
    @DisplayName("同一 key 在去抖窗口内多次调用只执行一次")
    void debounce_sameKey_stillDeduplicates() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("sameKey", counter::incrementAndGet);
        debouncer.debounce("sameKey", counter::incrementAndGet);
        debouncer.debounce("sameKey", counter::incrementAndGet);
        Thread.sleep(200);
        assertEquals(1, counter.get(), "Same key should still be deduplicated");
    }

    @Test
    @DisplayName("自定义线程池大小 = 1 时退化为串行")
    void debounce_singleThreadPool_serialExecution() throws InterruptedException {
        Debouncer singleThreadDebouncer = new Debouncer(100, TimeUnit.MILLISECONDS, 1);

        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch holdLatch = new CountDownLatch(1);

        Runnable task = () -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
            try {
                holdLatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentCount.decrementAndGet();
            }
        };

        singleThreadDebouncer.debounce("key1", task);
        singleThreadDebouncer.debounce("key2", task);
        Thread.sleep(200);

        // 单线程池下，并发度应 <= 1
        assertTrue(maxConcurrent.get() <= 1,
                "Single-thread pool should serialize tasks. Max concurrent: " + maxConcurrent.get());

        holdLatch.countDown();
        singleThreadDebouncer.close();
    }
}
