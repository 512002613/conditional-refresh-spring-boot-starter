package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.listener.Debouncer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debouncer 的单元测试。
 */
class DebouncerTest {

    private Debouncer debouncer;

    @BeforeEach
    void setUp() {
        debouncer = new Debouncer(100, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() {
        debouncer.close();
    }

    @Test
    @DisplayName("任务执行后计数器增加")
    void debounce_taskExecuted() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("key1", counter::incrementAndGet);
        Thread.sleep(200);
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("同一 key 短时间多次调用只执行最后一次")
    void debounce_sameKey_deduplicates() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("key1", counter::incrementAndGet);
        debouncer.debounce("key1", counter::incrementAndGet);
        debouncer.debounce("key1", counter::incrementAndGet);
        Thread.sleep(200);
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("不同 key 是独立的")
    void debounce_differentKeys_independent() throws InterruptedException {
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);
        debouncer.debounce("key1", counter1::incrementAndGet);
        debouncer.debounce("key2", counter2::incrementAndGet);
        Thread.sleep(200);
        assertEquals(1, counter1.get());
        assertEquals(1, counter2.get());
    }

    @Test
    @DisplayName("任务异常不应阻断调度器")
    void debounce_taskException_notPreventSubsequent() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("key1", () -> {
            throw new RuntimeException("test");
        });
        debouncer.debounce("key2", counter::incrementAndGet);
        Thread.sleep(200);
        // key1 的执行抛异常，但 key2 仍应执行
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("关闭后忽略新任务")
    void debounce_afterShutdown_ignored() throws InterruptedException {
        debouncer.close();
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("key1", counter::incrementAndGet);
        Thread.sleep(200);
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("等待后任务应执行")
    void debounce_notExecutedImmediately() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        debouncer.debounce("key1", counter::incrementAndGet);
        // 尚未超过静默窗口，任务不应执行
        Thread.sleep(50);
        assertEquals(0, counter.get());
    }
}
