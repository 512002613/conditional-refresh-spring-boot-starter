package com.liu.conditionalrefresh.listener;

import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 条件刷新监听器：监听 Spring 环境变更事件，按反向索引精准刷新受影响的 Bean。
 *
 * <h2>工作流程（PropertySource-First）</h2>
 * <ol>
 *     <li>等待 {@link ApplicationReadyEvent}（确保 Environment 和 Nacos 客户端就绪）。</li>
 *     <li>调用 {@link MetadataCollector#buildCommittedIndex} 解析占位符并构建反向索引。</li>
 *     <li>监听 {@link EnvironmentChangeEvent}（PropertySource 更新后发布，携带 changedKeys）：
 *         <ul>
 *             <li>通过反向索引（精确 + 前缀匹配）定位受影响 Bean。</li>
 *             <li>去抖 → 销毁旧 Bean（新实例惰性创建时自动读新值）。</li>
 *         </ul>
 *     </li>
 *     <li>兼容路径：监听 {@link RefreshScopeRefreshedEvent}（SC 2021.0.x LegacyContextRefresher
 *         全量 restart 场景），全量刷新所有 @RefreshOnKeys Bean。</li>
 * </ol>
 *
 * <h2>版本适配</h2>
 * <ul>
 *     <li><strong>SC 2022.0.x+</strong>（SB 3.x）：{@code EnvironmentChangeEvent} 携带正确 changedKeys，
 *         精确条件刷新。</li>
 *     <li><strong>SC 2021.0.x</strong>（SB 2.7）：{@code LegacyContextRefresher} 全量 restart 后
 *         {@code EnvironmentChangeEvent} keys 为空，降级为 {@code RefreshScopeRefreshedEvent}
 *         全量刷新所有 @RefreshOnKeys Bean。</li>
 * </ul>
 *
 * <h2>监听器顺序</h2>
 * <p>实现 {@link Ordered} 返回 {@link Ordered#LOWEST_PRECEDENCE}，确保在
 * {@code ConfigurationPropertiesRebinder}（rebind @ConfigurationProperties）<strong>之后</strong>执行。
 * 这样新 Bean 重建时能读到已 rebind 的 @ConfigurationProperties 值。
 *
 * <h2>与旧实现的区别</h2>
 * <ul>
 *     <li>旧实现：直接注册 Nacos SDK 监听器（{@code configService.addListener}），
 *         在 PropertySource 更新<strong>之前</strong>触发 → 时序倒置 bug。</li>
 *     <li>新实现：监听 Spring {@code EnvironmentChangeEvent}，在 PropertySource 更新<strong>之后</strong>触发
 *         → 新 Bean 读到正确的新值。</li>
 * </ul>
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
public class ConditionalRefreshListener
        implements SmartApplicationListener, Ordered, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConditionalRefreshListener.class);

    /** 条件刷新作用域。 */
    private final ConditionalRefreshScope scope;

    /** 元数据后处理器。 */
    private final RefreshOnKeysPostProcessor postProcessor;

    /** Spring Environment。 */
    private final Environment environment;

    /**
     * Bean 级锁映射，保证同一 Bean 同一时刻只有一个刷新操作。
     * <p>使用 {@link ConcurrentHashMap#computeIfAbsent} 保证懒创建和线程安全。
     */
    private final ConcurrentHashMap<String, ReentrantLock> beanLocks = new ConcurrentHashMap<>();

    /** 单一去抖器（不再需要 per-(dataId, group) 隔离）。 */
    private final Debouncer debouncer = new Debouncer();

    /**
     * 标记本轮刷新周期内 {@code EnvironmentChangeEvent} 是否已携带有效 keys。
     * <p>SC 2022.0.x+ 下，同一次配置推送会顺序发布 {@code EnvironmentChangeEvent}
     * 和 {@code RefreshScopeRefreshedEvent}。当主路径已处理时，跳过 fallback 全量刷新，
     * 避免重复刷新。
     */
    private volatile boolean environmentChangeEventHandled = false;

    /**
     * 构造监听器。
     *
     * @param scope        条件刷新作用域
     * @param postProcessor 元数据后处理器
     * @param environment  Spring 环境
     */
    public ConditionalRefreshListener(ConditionalRefreshScope scope,
                                      RefreshOnKeysPostProcessor postProcessor,
                                      Environment environment) {
        this.scope = scope;
        this.postProcessor = postProcessor;
        this.environment = environment;
    }

    /**
     * 判断是否支持指定事件类型。
     *
     * <p>支持三种事件：
     * <ul>
     *   <li>{@link ApplicationReadyEvent} — 应用就绪，构建反向索引</li>
     *   <li>{@link EnvironmentChangeEvent} — 环境变更（主路径）</li>
     *   <li>{@link RefreshScopeRefreshedEvent} — RefreshScope 刷新完成（SC 2021.0.x fallback）</li>
     * </ul>
     *
     * @param eventType 事件类型
     * @return 若支持返回 {@code true}
     */
    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return ApplicationReadyEvent.class.isAssignableFrom(eventType)
                || EnvironmentChangeEvent.class.isAssignableFrom(eventType)
                || RefreshScopeRefreshedEvent.class.isAssignableFrom(eventType);
    }

    /**
     * 统一事件分发。
     *
     * @param event 应用事件
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent) {
            onApplicationReady((ApplicationReadyEvent) event);
        } else if (event instanceof EnvironmentChangeEvent) {
            onEnvironmentChanged((EnvironmentChangeEvent) event);
        } else if (event instanceof RefreshScopeRefreshedEvent) {
            onRefreshScopeRefreshed((RefreshScopeRefreshedEvent) event);
        }
    }

    /**
     * 应用就绪事件回调：构建反向索引。
     *
     * <p>不再注册 Nacos SDK 监听器 — 改为监听 Spring 的 {@code EnvironmentChangeEvent}。
     *
     * @param event 应用就绪事件
     */
    public void onApplicationReady(ApplicationReadyEvent event) {
        // 全局开关检查
        if (!isEnabled()) {
            log.info("Conditional refresh is disabled (conditional.refresh.enabled=false).");
            return;
        }

        MetadataCollector collector = postProcessor.getCollector();
        if (collector.isEmpty()) {
            log.info("No beans annotated with @RefreshOnKeys found. Conditional refresh skip.");
            return;
        }

        // 构建最终的反向索引（解析占位符、空 dataId 回退等）
        collector.buildCommittedIndex(environment);

        log.info("Conditional refresh listeners initialized. " +
                "Listening on EnvironmentChangeEvent for key changes.");
    }

    /**
     * 处理 {@link EnvironmentChangeEvent}（主路径 — SC 2022.0.x+）。
     *
     * <p>在 PropertySource 更新后、ConfigurationPropertiesRebinder rebind 后调用。
     * 通过反向索引精准定位受影响 Bean。
     *
     * @param event 环境变更事件
     */
    public void onEnvironmentChanged(EnvironmentChangeEvent event) {
        if (!isEnabled()) {
            return;
        }

        Set<String> changedKeys = event.getKeys();
        if (changedKeys == null || changedKeys.isEmpty()) {
            log.debug("EnvironmentChangeEvent with empty keys, skip.");
            return;
        }

        log.info("Environment changed, keys: {}", changedKeys);

        // 标记主路径已处理，抑制本轮 fallback
        environmentChangeEventHandled = true;

        // 通过反向索引查找受影响的 Bean
        Set<String> affectedBeans = findAffectedBeans(changedKeys);
        if (affectedBeans.isEmpty()) {
            log.debug("Changed keys {} not watched by any bean", changedKeys);
            return;
        }

        log.info("Affected beans for changed keys {}: {}", changedKeys, affectedBeans);

        // 对每个受影响的 Bean 执行去抖刷新
        for (String beanName : affectedBeans) {
            debouncer.debounce(beanName, () -> refreshBean(beanName));
        }
    }

    /**
     * 处理 {@link RefreshScopeRefreshedEvent}（兼容路径 — SC 2021.0.x fallback）。
     *
     * <p>SC 2021.0.x 的 LegacyContextRefresher 全量 restart 后，EnvironmentChangeEvent
     * 不携带有效 keys。此事件在 RefreshScope.refreshAll() 完成后发布，作为 fallback 触发器。
     *
     * <p>由于无法获取具体 changedKeys，全量刷新所有 @RefreshOnKeys Bean。
     *
     * @param event RefreshScope 刷新完成事件
     */
    public void onRefreshScopeRefreshed(RefreshScopeRefreshedEvent event) {
        if (!isEnabled()) {
            return;
        }

        // SC 2022.0.x+ 下 EnvironmentChangeEvent 已携带 keys 并处理完毕，
        // 跳过 fallback 避免重复刷新。
        if (environmentChangeEventHandled) {
            environmentChangeEventHandled = false;
            return;
        }

        MetadataCollector collector = postProcessor.getCollector();
        if (collector.isEmpty()) {
            return;
        }

        // committedIndex 尚未构建（首次启动时 RefreshScopeRefreshedEvent 可能在 ApplicationReadyEvent 之前）
        if (collector.getCommittedIndex() == null) {
            log.debug("Committed index not built yet, skip RefreshScopeRefreshedEvent.");
            return;
        }

        log.info("RefreshScopeRefreshedEvent received (SC 2021.0.x fallback), " +
                "refreshing all @RefreshOnKeys beans.");

        Set<String> allBeans = collector.getAllBeanNames();
        if (allBeans.isEmpty()) {
            return;
        }

        for (String beanName : allBeans) {
            debouncer.debounce(beanName, () -> refreshBean(beanName));
        }
    }

    /**
     * 释放所有资源（应用关闭时调用）。
     *
     * <p>关闭去抖器线程池，清空锁映射。
     */
    @Override
    public void close() {
        log.debug("Closing ConditionalRefreshListener, releasing debouncer and locks.");
        debouncer.close();
        beanLocks.clear();
    }

    /**
     * 返回监听器执行顺序。
     *
     * <p>返回 {@link Ordered#LOWEST_PRECEDENCE}，确保在 {@code ConfigurationPropertiesRebinder}
     * （rebind @ConfigurationProperties）<strong>之后</strong>执行。
     *
     * @return 排序值（越大越晚执行）
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────

    /**
     * 通过反向索引查找受影响的 Bean 名称（精确 + 前缀匹配）。
     *
     * @param changedKeys 变更的配置键集合
     * @return 受影响的 Bean 名称集合（去重）；若 committedIndex 尚未构建返回空集
     */
    private Set<String> findAffectedBeans(Set<String> changedKeys) {
        MetadataCollector collector = postProcessor.getCollector();
        Set<String> affected = new HashSet<>();
        if (collector.isEmpty()) {
            return affected;
        }
        Map<String, Map<String, MetadataCollector.IndexEntry>> index = collector.getCommittedIndex();
        if (index == null) {
            return affected;
        }
        for (Map<String, MetadataCollector.IndexEntry> groupMap : index.values()) {
            for (MetadataCollector.IndexEntry entry : groupMap.values()) {
                affected.addAll(entry.findAffectedBeans(changedKeys));
            }
        }
        return affected;
    }

    /**
     * 在锁保护下刷新指定 Bean。
     *
     * <p>使用 Bean 级别的 {@link ReentrantLock}（而非全局锁），
     * 保证不同 Bean 可并行刷新，同一 Bean 串行刷新。
     *
     * <p><strong>注意</strong>：{@link ConditionalRefreshScope#refresh(String)}
     * 仅销毁旧实例，新实例将在下次通过 scoped-proxy 访问时惰性创建。
     *
     * @param beanName Bean 名称
     */
    private void refreshBean(String beanName) {
        ReentrantLock lock = beanLocks.computeIfAbsent(beanName, k -> new ReentrantLock());
        lock.lock();
        try {
            log.info("Refreshing bean '{}' ...", beanName);
            boolean effective = scope.refresh(beanName);
            if (effective) {
                log.info("Bean '{}' config change applied. New instance will be created lazily on next access.",
                        beanName);
                recordSuccess(beanName);
            } else {
                log.warn("Bean '{}' refresh returned false (unexpected, possible destroyMethod failure).",
                        beanName);
                recordFailure(beanName);
            }
        } catch (Exception e) {
            log.error("Failed to refresh bean '{}': {}", beanName, e.getMessage(), e);
            recordFailure(beanName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查全局开关是否开启。
     *
     * @return 若 {@code conditional.refresh.enabled} 为 {@code true} 或未配置则返回 {@code true}
     */
    private boolean isEnabled() {
        return environment.getProperty("conditional.refresh.enabled", Boolean.class, true);
    }

    /**
     * 记录刷新成功指标。
     *
     * @param beanName 刷新成功的 Bean 名称
     */
    private void recordSuccess(String beanName) {
        try {
            io.micrometer.core.instrument.MeterRegistry registry =
                    io.micrometer.core.instrument.Metrics.globalRegistry;
            if (registry != null) {
                io.micrometer.core.instrument.Counter.builder("conditional.refresh.success")
                        .tag("bean", beanName)
                        .register(registry)
                        .increment();
            }
        } catch (Exception e) {
            log.debug("Failed to record Micrometer success metric for bean '{}': {}",
                    beanName, e.getMessage());
        }
    }

    /**
     * 记录刷新失败指标。
     *
     * @param beanName 刷新失败的 Bean 名称
     */
    private void recordFailure(String beanName) {
        try {
            io.micrometer.core.instrument.MeterRegistry registry =
                    io.micrometer.core.instrument.Metrics.globalRegistry;
            if (registry != null) {
                io.micrometer.core.instrument.Counter.builder("conditional.refresh.failure")
                        .tag("bean", beanName)
                        .register(registry)
                        .increment();
            }
        } catch (Exception e) {
            log.debug("Failed to record Micrometer failure metric for bean '{}': {}",
                    beanName, e.getMessage());
        }
    }
}
