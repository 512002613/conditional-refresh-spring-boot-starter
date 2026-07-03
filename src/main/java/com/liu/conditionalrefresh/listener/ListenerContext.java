package com.liu.conditionalrefresh.listener;

import com.liu.conditionalrefresh.processor.MetadataCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 (dataId, group) 监听器上下文。
 *
 * <p>每个 Nacos 配置组合对应一个独立的监听器实例，
 * 内含该组配置的所有元数据和运行时状态。
 *
 * <h3>持有状态</h3>
 * <ul>
 *     <li><strong>lastSnapshot</strong>：上一次成功的配置快照（{@code Map<String, Object>}）。
 *         使用 {@link AtomicReference} 保证原子更新和可见性。</li>
 *     <li><strong>indexEntry</strong>：反向索引条目，提供 key → beanNames 的快速查找。</li>
 *     <li><strong>debouncer</strong>：去抖器，抑制短时间内的重复刷新。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有公共状态通过 {@link AtomicReference} 和不可变引用保证线程安全。
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
public class ListenerContext {

    private static final Logger log = LoggerFactory.getLogger(ListenerContext.class);

    /** 上一次配置快照（原子引用）。 */
    private final AtomicReference<Map<String, Object>> lastSnapshot;

    /** 反向索引条目（不可变，来自 MetadataCollector）。 */
    private final MetadataCollector.IndexEntry indexEntry;

    /** 该组下的去抖器。 */
    private final Debouncer debouncer;

    /**
     * 构造监听器上下文。
     *
     * @param entry 包含反向索引条目的 {@link MetadataCollector.IndexEntry}
     * @param initialSnapshot 初始配置快照（在监听器注册时通过主动调用获取）
     */
    public ListenerContext(MetadataCollector.IndexEntry entry,
                          Map<String, Object> initialSnapshot) {
        this.indexEntry = entry;
        this.lastSnapshot = new AtomicReference<>(
                initialSnapshot != null ? initialSnapshot : Collections.emptyMap());
        this.debouncer = new Debouncer();
        log.debug("ListenerContext created for index entry with {} key(s).",
                entry.keyToBeanNames().size());
    }

    /**
     * 使用反向索引快速查找给定变更 keys 受影响的 Bean 名称。
     *
     * <p>委托给 {@link MetadataCollector.IndexEntry#findAffectedBeans(Set)} 执行。
     *
     * @param changedKeys 变更的配置键集合
     * @return 受影响的 Bean 名称集合（已去重）
     */
    public Set<String> findAffectedBeans(Set<String> changedKeys) {
        return indexEntry.findAffectedBeans(changedKeys);
    }

    /**
     * 获取当前快照（仅供 diff 使用，不要缓存引用）。
     *
     * @return 当前的配置快照引用
     */
    public Map<String, Object> getCurrentSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * 原子更新快照并返回替换前的旧快照。
     *
     * <p>使用 {@link AtomicReference#getAndSet} 保证"读旧值"和"写新值"整体原子，
     * 避免并发回调场景下快照被覆盖。
     *
     * @param newSnapshot 最新的配置快照
     * @return 替换前的旧快照
     */
    public Map<String, Object> replaceSnapshotAndGetOld(Map<String, Object> newSnapshot) {
        return lastSnapshot.getAndSet(newSnapshot);
    }

    /**
     * 获取去抖器。
     *
     * @return 该组对应的去抖器
     */
    public Debouncer getDebouncer() {
        return debouncer;
    }

    /**
     * 获取反向索引（仅供测试或监控使用）。
     *
     * @return key → Bean 集合的映射（不可变视图）
     */
    public Map<String, Set<String>> getKeyToBeans() {
        return Collections.unmodifiableMap(indexEntry.keyToBeanNames());
    }

    /**
     * 释放资源（去抖器的调度器线程池）。
     */
    public void close() {
        log.debug("Closing ListenerContext and releasing its Debouncer resources.");
        debouncer.close();
    }
}
