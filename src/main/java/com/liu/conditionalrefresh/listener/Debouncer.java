package com.liu.conditionalrefresh.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 去抖器（Debouncer），用于抑制短时间内的重复刷新。
 *
 * <p>当同一 Bean 在短时间内收到多次配置变更通知时，
 * 不立即执行刷新，而是等待一个静默窗口。若窗口内有新的变更通知，
 * 则取消前一次调度、重新计时。只有在窗口内没有新通知时，才会真正执行刷新。
 *
 * <h3>为什么需要去抖？</h3>
 * <ul>
 *     <li>Nacos 配置推送可能存在"通知风暴"（同一配置在短时间内多次推送）。</li>
 *     <li>同一 Key 的变更可能触发多个监听路径，导致重复刷新。</li>
 *     <li>频繁地对带连接池的 Bean 执行 {@code destroy + create} 会造成资源泄漏。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>使用<strong>固定大小线程池</strong>（默认大小 = CPU 核心数），而非单线程调度器。
 * 这样不同 Bean 的刷新任务可以在不同线程中<strong>并行执行</strong>，
 * 而同一 Bean 的并发控制由上层 {@link java.util.concurrent.locks.ReentrantLock} 保证，
 * 避免与 Bean 级锁产生两层串行化的设计冲突。
 *
 * <h3>线程安全性</h3>
 * <p>{@link #pending} 使用 {@link ConcurrentHashMap}，调度过程中若
 * {@link #shutdown()} 被调用，未执行的任务将被取消。
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
public class Debouncer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Debouncer.class);

    /**
     * 默认线程池大小：CPU 核心数（至少 2 线程保证并行能力）。
     */
    private static final int DEFAULT_POOL_SIZE = Math.max(2,
            Runtime.getRuntime().availableProcessors());

    /** 调度器线程池（固定大小，允许多个 Bean 并行刷新）。 */
    private final ScheduledExecutorService scheduler;

    /** 每个 key 对应的待执行任务。 */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /** 去抖静默窗口大小。 */
    private final long delay;

    /** 时间单位。 */
    private final TimeUnit unit;

    /** 是否已关闭。 */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * 构造默认去抖器（静默窗口 500ms，线程池大小 = CPU 核心数）。
     */
    public Debouncer() {
        this(500, TimeUnit.MILLISECONDS);
    }

    /**
     * 构造指定静默窗口的去抖器（线程池大小 = CPU 核心数）。
     *
     * @param delay 静默窗口大小
     * @param unit  时间单位
     */
    public Debouncer(long delay, TimeUnit unit) {
        this(delay, unit, DEFAULT_POOL_SIZE);
    }

    /**
     * 构造指定静默窗口和线程池大小的去抖器。
     *
     * <p>用于测试或需要自定义线程池大小的场景。
     *
     * @param delay    静默窗口大小
     * @param unit     时间单位
     * @param poolSize 线程池大小（至少 1）
     */
    public Debouncer(long delay, TimeUnit unit, int poolSize) {
        this.delay = delay;
        this.unit = unit;
        int actualPoolSize = Math.max(1, poolSize);
        this.scheduler = Executors.newScheduledThreadPool(actualPoolSize, r -> {
            Thread t = new Thread(r, "conditional-refresh-debouncer");
            t.setDaemon(true);
            return t;
        });
        log.debug("Debouncer created with delay={}ms, poolSize={}", delay, actualPoolSize);
    }

    /**
     * 提交一个去抖任务。
     *
     * <p>若同一 {@code key} 已有尚未执行的任务，将取消前一次并重新计时。
     *
     * <p><strong>异常处理</strong>：任务内部的异常会被捕获并记录日志，
     * 且会增加一个 {@code conditional.refresh.failure} 计数器（如果 Micrometer 可用）。
     * 这样做可防止异常导致调度器"饿死"后续任务。
     *
     * @param key  去抖键（通常为 Bean 名称）
     * @param task 要执行的任务（非空）
     */
    public void debounce(String key, Runnable task) {
        if (shutdown.get()) {
            log.warn("Debouncer is already shutdown, ignore task for key '{}'", key);
            return;
        }
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }

        // 取消同一 key 之前未执行的任务
        ScheduledFuture<?> prev = pending.get(key);
        if (prev != null) {
            prev.cancel(false);
        }

        // 在执行器中调度新的刷新任务
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pending.remove(key);
            if (shutdown.get()) {
                return;
            }
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error while executing debounced refresh for key '{}': {}",
                        key, e.getMessage(), e);
                recordFailure(key);
            }
        }, delay, unit);

        pending.put(key, future);
    }

    /**
     * 检查指定 key 是否仍有待执行的去抖任务。
     *
     * @param key 去抖键
     * @return 若有未执行的调度任务返回 {@code true}
     */
    public boolean hasPending(String key) {
        ScheduledFuture<?> future = pending.get(key);
        return future != null && !future.isDone();
    }

    /**
     * 返回当前待执行任务数量。
     *
     * @return 待执行任务数
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 关闭去抖器，释放调度器资源。
     *
     * <p>已调度但未执行的任务将被取消（cancel）。
     * 调用后 {@link #debounce} 将忽略所有新任务。
     */
    @Override
    public void close() {
        if (shutdown.compareAndSet(false, true)) {
            log.debug("Closing Debouncer, cancelling {} pending task(s).", pending.size());
            pending.values().forEach(f -> f.cancel(false));
            pending.clear();
            scheduler.shutdownNow();
        }
    }

    /**
     * 记录刷新失败（若有 Micrometer 则发指标，否则仅日志）。
     *
     * <p>Micrometer 非强依赖，指标记录失败时仅记录 debug 日志，不影响主流程。
     *
     * @param key 去抖键（Bean 名称）
     */
    private void recordFailure(String key) {
        try {
            // 通过间接方式避免强依赖 Micrometer
            io.micrometer.core.instrument.MeterRegistry registry =
                    io.micrometer.core.instrument.Metrics.globalRegistry;
            if (registry != null) {
                io.micrometer.core.instrument.Counter.builder("conditional.refresh.failure")
                        .tag("bean", key)
                        .register(registry)
                        .increment();
            }
        } catch (Exception e) {
            // 忽略 Micrometer 相关异常
            log.debug("Failed to record Micrometer failure metric for key '{}': {}",
                    key, e.getMessage());
        }
    }
}
