package com.liu.conditionalrefresh.listener;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 条件刷新监听器：在应用就绪后为每个 (dataId, group) 注册 Nacos 配置监听器，
 * 并在配置变更时按反向索引精准刷新受影响的 Bean。
 *
 * <h3>工作流程</h3>
 * <ol>
 *     <li>等待 {@link ApplicationReadyEvent}（确保 Nacos 客户端和 Environment 就绪）。</li>
 *     <li>调用 {@link MetadataCollector#buildCommittedIndex} 解析占位符并构建反向索引。</li>
 *     <li>为每个 (dataId, group)：
 *         <ul>
 *             <li>通过 {@code configService.getConfig()} 获取<b>初始配置快照</b>（避免首次推送全量刷新）。</li>
 *             <li>注册 Nacos {@link Listener}，在回调中执行 diff → 反向索引 → 去抖刷新。</li>
 *         </ul>
 *     </li>
 * </ol>
 *
 * <h3>关键修复点</h3>
 * <ul>
 *     <li>注册监听器前先获取初始配置快照，避免首次推送触发所有 Bean 全量刷新。</li>
 *     <li>使用反向索引 O(1) 定位受影响 Bean，无需遍历所有元数据。</li>
 *     <li>每个 Bean 使用独立的 {@link ReentrantLock} 保证并发安全。</li>
 *     <li>去抖器抑制短时间内的重复刷新。</li>
 * </ul>
 *
 * <h3>触发链路</h3>
 * <pre>
 * Nacos 推送 → receiveConfigInfo()
 *   → ConfigDiffUtils.parse() → 新快照
 *   → ConfigDiffUtils.diff(old, new) → 变更 keys
 *   → ListenerContext.findAffectedBeans() → 受影响 Bean 集合
 *   → Debouncer.debounce() + ReentrantLock → ConditionalRefreshScope.refresh()
 * </pre>
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
public class ConditionalRefreshListener
        implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConditionalRefreshListener.class);

    /** Nacos 配置管理器。 */
    private final NacosConfigManager nacosConfigManager;

    /** 条件刷新作用域。 */
    private final ConditionalRefreshScope scope;

    /** 元数据后处理器。 */
    private final RefreshOnKeysPostProcessor postProcessor;

    /** Spring Environment。 */
    private final Environment environment;

    /**
     * 所有监听器上下文，按 (dataId, group) 索引。
     * <p>结构：{@code dataId → group → ListenerContext}
     */
    private final Map<String, Map<String, ListenerContext>> contexts = new ConcurrentHashMap<>();

    /**
     * Bean 级锁映射，保证同一 Bean 同一时刻只有一个刷新操作。
     * <p>使用 {@link ConcurrentHashMap#computeIfAbsent} 保证懒创建和线程安全。
     */
    private final ConcurrentHashMap<String, ReentrantLock> beanLocks = new ConcurrentHashMap<>();

    /**
     * 配置服务超时时间（ms）。
     */
    private static final long CONFIG_GET_TIMEOUT = 5000L;

    /**
     * 构造监听器。
     *
     * @param nacosConfigManager Nacos 配置管理器
     * @param scope              条件刷新作用域
     * @param postProcessor      元数据后处理器
     * @param environment        Spring 环境
     */
    public ConditionalRefreshListener(NacosConfigManager nacosConfigManager,
                                     ConditionalRefreshScope scope,
                                     RefreshOnKeysPostProcessor postProcessor,
                                     Environment environment) {
        this.nacosConfigManager = nacosConfigManager;
        this.scope = scope;
        this.postProcessor = postProcessor;
        this.environment = environment;
    }

    /**
     * 应用就绪事件回调：注册所有 Nacos 配置监听器。
     *
     * <p>在以下条件满足后执行：
     * <ul>
     *     <li>Spring 容器启动完成。</li>
     *     <li>Environment 完全就绪（可解析占位符）。</li>
     *     <li>Nacos 配置服务可用。</li>
     * </ul>
     *
     * @param event 应用就绪事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
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
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(environment);

        log.info("Building conditional refresh listeners for {} dataId(s)", index.size());

        for (Map.Entry<String, Map<String, MetadataCollector.IndexEntry>> dataEntry : index.entrySet()) {
            String dataId = dataEntry.getKey();
            for (Map.Entry<String, MetadataCollector.IndexEntry> groupEntry : dataEntry.getValue().entrySet()) {
                String group = groupEntry.getKey();
                MetadataCollector.IndexEntry entry = groupEntry.getValue();
                registerListener(dataId, group, entry);
            }
        }

        log.info("Conditional refresh listeners registered successfully. " +
                "Total contexts: {}", contexts.size());
    }

    /**
     * 释放所有资源（应用关闭时调用）。
     *
     * <p>关闭所有 {@link ListenerContext} 内部的 {@link Debouncer} 线程池，
     * 清空上下文和锁映射。实现 {@link AutoCloseable} 以支持 {@code try-with-resources}。
     */
    @Override
    public void close() {
        log.debug("Closing ConditionalRefreshListener, {} context(s) to release.", contexts.size());
        for (Map<String, ListenerContext> groupMap : contexts.values()) {
            for (ListenerContext ctx : groupMap.values()) {
                ctx.close();
            }
        }
        contexts.clear();
        beanLocks.clear();
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────

    /**
     * 为指定的 (dataId, group) 注册 Nacos 监听器。
     *
     * <p><strong>关键</strong>：注册前先主动获取一次当前配置作为初始快照，
     * 避免首次推送被误判为"所有 key 都新增"从而导致无谓的全量刷新。
     *
     * @param dataId Nacos Data ID
     * @param group  Nacos Group
     * @param entry  该组的反向索引条目
     */
    private void registerListener(String dataId, String group,
                                  MetadataCollector.IndexEntry entry) {
        // 1. 获取初始配置快照（避免首次推送的全量刷新）
        Map<String, Object> initialSnapshot = fetchInitialSnapshot(dataId, group);

        // 2. 创建监听器上下文
        ListenerContext ctx = new ListenerContext(entry, initialSnapshot);
        contexts.computeIfAbsent(dataId, k -> new ConcurrentHashMap<>()).put(group, ctx);

        // 3. 注册 Nacos 监听器
        try {
            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    // 在 Nacos 线程池中执行，不占用业务线程
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    handleChange(dataId, group, configInfo);
                }
            });

            log.info("Registered Nacos listener for dataId={}, group={}", dataId, group);
        } catch (NacosException e) {
            log.error("Failed to register Nacos listener for dataId={}, group={}: {}",
                    dataId, group, e.getMessage(), e);
        }
    }

    /**
     * 主动获取当前配置内容作为初始快照。
     *
     * <p>若获取失败（Nacos 未启动、网络问题等），则使用空快照作为兜底，
     * 此时首次推送会触发全量刷新（但仍是安全的，可恢复）。
     *
     * @param dataId Nacos Data ID
     * @param group  Nacos Group
     * @return 当前配置的快照 Map
     */
    private Map<String, Object> fetchInitialSnapshot(String dataId, String group) {
        try {
            String config = nacosConfigManager.getConfigService()
                    .getConfig(dataId, group, CONFIG_GET_TIMEOUT);
            if (config != null && !config.isEmpty()) {
                log.debug("Initial snapshot fetched for dataId={}, group={}", dataId, group);
                return ConfigDiffUtils.parse(config);
            }
        } catch (NacosException e) {
            log.warn("Failed to fetch initial snapshot for dataId={}, group={}: {}",
                    dataId, group, e.getMessage());
        }
        return Collections.emptyMap();
    }

    /**
     * 处理配置变更回调：解析 → diff → 反向索引 → 去抖刷新。
     *
     * @param dataId       Nacos Data ID
     * @param group        Nacos Group
     * @param newConfigText 新配置的全文
     */
    private void handleChange(String dataId, String group, String newConfigText) {
        ListenerContext ctx = getContext(dataId, group);
        if (ctx == null) {
            log.warn("No listener context found for dataId={}, group={}", dataId, group);
            return;
        }

        // 1. 解析新配置
        Map<String, Object> newSnapshot = ConfigDiffUtils.parse(newConfigText);

        // 2. 原子替换快照，并返回替换前的旧快照用于 diff
        Map<String, Object> oldSnapshot = ctx.replaceSnapshotAndGetOld(newSnapshot);

        // 3. 计算真正变化的 keys
        Set<String> changedKeys = ConfigDiffUtils.diff(oldSnapshot, newSnapshot);
        if (changedKeys.isEmpty()) {
            log.debug("No effective key changes for dataId={}, group={}", dataId, group);
            return;
        }

        log.info("Config changed for dataId={}, group={}: {}", dataId, group, changedKeys);

        // 4. 通过反向索引查找受影响的 Bean
        Set<String> affectedBeans = ctx.findAffectedBeans(changedKeys);
        if (affectedBeans.isEmpty()) {
            log.debug("Changed keys {} not watched by any bean", changedKeys);
            return;
        }

        log.info("Affected beans for changed keys {}: {}", changedKeys, affectedBeans);

        // 5. 对每个受影响的 Bean 执行去抖刷新
        Debouncer debouncer = ctx.getDebouncer();
        for (String beanName : affectedBeans) {
            debouncer.debounce(beanName, () -> refreshBean(beanName));
        }
    }

    /**
     * 在锁保护下刷新指定 Bean。
     *
     * <p>使用 Bean 级别的 {@link ReentrantLock}（而非全局锁），
     * 保证不同 Bean 可并行刷新，同一 Bean 串行刷新。
     *
     * <p><strong>注意</strong>：{@link ConditionalRefreshScope#refresh(String)}
     * 仅销毁旧实例，新实例将在下次通过 scoped-proxy 访问时惰性创建。
     * 此处记录的 {@code conditional.refresh.success} 指标表示"旧实例已销毁"，
     * 不代表新实例已成功创建。新实例创建失败将在后续访问时通过异常暴露。
     *
     * @param beanName Bean 名称
     */
    private void refreshBean(String beanName) {
        ReentrantLock lock = beanLocks.computeIfAbsent(beanName, k -> new ReentrantLock());
        lock.lock();
        try {
            log.info("Refreshing bean '{}' ...", beanName);
            boolean destroyed = scope.refresh(beanName);
            if (destroyed) {
                // 旧实例已销毁，新实例将在下次 proxy 访问时惰性创建
                log.info("Bean '{}' destroyed. New instance will be created lazily on next access.",
                        beanName);
                recordSuccess(beanName);
            } else {
                log.warn("Bean '{}' not found in conditional refresh scope.", beanName);
            }
        } catch (Exception e) {
            log.error("Failed to refresh bean '{}': {}", beanName, e.getMessage(), e);
            recordFailure(beanName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定 (dataId, group) 的监听器上下文。
     *
     * @param dataId Nacos Data ID
     * @param group  Nacos Group
     * @return 对应的 {@link ListenerContext}，若不存在返回 {@code null}
     */
    private ListenerContext getContext(String dataId, String group) {
        Map<String, ListenerContext> groupMap = contexts.get(dataId);
        if (groupMap == null) return null;
        return groupMap.get(group);
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
