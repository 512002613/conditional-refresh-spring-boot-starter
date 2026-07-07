package com.liu.conditionalrefresh.processor;

import com.liu.conditionalrefresh.annotation.RefreshOnKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据收集器，负责扫描并存储所有 {@link RefreshOnKeys} 注解的原始数据，
 * 并在环境就绪后构建<strong>反向索引</strong>（key → beanNames）供监听器使用。
 *
 * <h2>数据结构</h2>
 * <p>内部维护两层结构：
 * <ul>
 *   <li><strong>原始数据</strong>（raw）：用于在 BDRPP 阶段轻量收集，避免触发 Bean 实例化。
 *      结构：{@code dataId → group → (beanName → RawEntry)}</li>
 *   <li><strong>反向索引</strong>（index）：在环境就绪后构建，结构：
 *       {@code dataId → group → IndexEntry(keyToBeanNames + prefixToBeanNames)}</li>
 * </ul>
 *
 * <h2>使用流程</h2>
 * <ol>
 *   <li>BDRPP 阶段调用 {@link #add} 方法收集原始数据。</li>
 *   <li>环境就绪后（{@code ApplicationReadyEvent}）调用
 *       {@link #buildCommittedIndex(Environment)} 解析占位符并构建反向索引。</li>
 *   <li>监听器使用构建好的反向索引完成"变更 key → 受影响 Bean"的 O(1) 查找。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>内部使用 {@link ConcurrentHashMap}，支持并发安全的写入。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see com.liu.conditionalrefresh.listener.ConditionalRefreshListener
 */
public class MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(MetadataCollector.class);

    // ─── 原始数据：在 BDRPP 阶段收集，key 保留占位符 ────────────────────────

    /**
     * 单个 Bean 的原始监听元数据（精确 keys + 前缀，互斥）。
     */
    private static final class RawEntry {
        /** 精确监听的 keys（value 模式）。 */
        final Set<String> keys = new HashSet<>();
        /** 监听的前缀（prefix 模式）。 */
        String prefix = "";
    }

    /**
     * dataId → group → (beanName → RawEntry)
     * <p>RawEntry.keys 中可能包含未解析的占位符，如 {@code "${custom.key}"}。
     */
    private final Map<String, Map<String, Map<String, RawEntry>>> raw =
            new ConcurrentHashMap<>();

    // ─── 反向索引：在环境就绪后构建，用于监听器快速查找 ─────────────────────

    /**
     * dataId → group → IndexEntry（包含 keyToBeanNames + prefixToBeanNames 双索引）
     * <p>通过 {@link #buildCommittedIndex(Environment)} 触发构建。
     * 构建完成后 {@link #raw} 会被清空以释放内存。
     */
    private volatile Map<String, Map<String, IndexEntry>> committedIndex;

    /**
     * 添加一条 Bean 监听元数据（精确模式）。
     *
     * <p>在 BDRPP 阶段被 {@link RefreshOnKeysPostProcessor} 调用，
     * 此时 Environment 未必完全就绪，占位符暂不解析。
     *
     * @param beanName Bean 名称
     * @param dataId   原始 dataId（可能为空字符串表示使用默认值）
     * @param group    原始 group（可能为空字符串表示使用默认值）
     * @param keys     配置键名数组（可能包含未解析的占位符）
     */
    public void add(String beanName, String dataId, String group, String[] keys) {
        add(beanName, dataId, group, keys, "");
    }

    /**
     * 添加一条 Bean 监听元数据（支持前缀模式）。
     *
     * <p>在 BDRPP 阶段被 {@link RefreshOnKeysPostProcessor} 调用，
     * 此时 Environment 未必完全就绪，占位符暂不解析。
     *
     * <p>精确模式与前缀模式互斥：{@code keys} 非空时 {@code prefix} 必须为空，反之亦然。
     *
     * @param beanName Bean 名称
     * @param dataId   原始 dataId（可能为空字符串表示使用默认值）
     * @param group    原始 group（可能为空字符串表示使用默认值）
     * @param keys     配置键名数组（精确模式，可能包含未解析的占位符）
     * @param prefix   配置前缀（前缀模式，可能包含未解析的占位符）
     */
    public void add(String beanName, String dataId, String group, String[] keys, String prefix) {
        boolean hasKeys = (keys != null && keys.length > 0);
        boolean hasPrefix = (prefix != null && !prefix.isEmpty());
        if (!hasKeys && !hasPrefix) {
            return;
        }
        raw.computeIfAbsent(dataId, k -> new ConcurrentHashMap<>())
           .computeIfAbsent(group, k -> new ConcurrentHashMap<>())
           .compute(beanName, (name, existing) -> {
               RawEntry merged = (existing != null) ? existing : new RawEntry();
               if (hasKeys) {
                   merged.keys.addAll(Arrays.asList(keys));
               }
               if (hasPrefix) {
                   merged.prefix = prefix;
               }
               return merged;
           });
    }

    /**
     * 检查是否收集到任何元数据。
     *
     * <p>检查两个来源：
     * <ul>
     *   <li>{@link #raw}：BDRPP 阶段收集的原始数据</li>
     *   <li>{@link #committedIndex}：构建完成的反向索引（构建后 {@link #raw} 会被清空）</li>
     * </ul>
     *
     * @return 若没有任何 Bean 标记了 {@code @RefreshOnKeys}，返回 {@code true}
     */
    public boolean isEmpty() {
        if (!raw.isEmpty()) {
            return false;
        }
        if (committedIndex != null) {
            for (Map<String, IndexEntry> groupMap : committedIndex.values()) {
                for (IndexEntry entry : groupMap.values()) {
                    if (!entry.keyToBeanNames.isEmpty() || !entry.prefixToBeanNames.isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 获取所有需要监听的 (dataId, group) 组合。
     *
     * @return 不可修改的 (dataId, group) 集合。若尚未调用
     *         {@link #buildCommittedIndex(Environment)}，将解析为原始值。
     */
    public Set<DataGroupKey> getDataGroupKeys() {
        Set<DataGroupKey> result = new HashSet<>();
        for (Map.Entry<String, Map<String, Map<String, RawEntry>>> dataEntry : raw.entrySet()) {
            String dataId = dataEntry.getKey();
            for (String group : dataEntry.getValue().keySet()) {
                result.add(new DataGroupKey(dataId, group));
            }
        }
        return result;
    }

    /**
     * 构建最终的反向索引，解析所有占位符并清空原始数据。
     *
     * <p>应在环境就绪后（{@code ApplicationReadyEvent}）调用，且仅调用一次。
     *
     * <p>处理空 dataId 的默认值回退逻辑：
     * <ol>
     *     <li>若 dataId 为空，回退为 {@code spring.cloud.nacos.config.prefix}，
     *         再回退为 {@code spring.application.name}。</li>
     *     <li>若 group 为空，回退为 {@code spring.cloud.nacos.config.group}，
     *         再回退为 {@code DEFAULT_GROUP}。</li>
     * </ol>
     *
     * @param env Spring Environment，用于解析占位符和读取默认值
     * @return 构建完成的反向索引（dataId → group → IndexEntry）
     * @throws IllegalStateException 若已调用过此方法
     */
    public Map<String, Map<String, IndexEntry>> buildCommittedIndex(Environment env) {
        if (committedIndex != null) {
            log.error("Attempted to build committed index more than once");
            throw new IllegalStateException("committed index already built");
        }

        String defaultDataId = env.getProperty("spring.cloud.nacos.config.prefix",
                env.getProperty("spring.application.name", ""));
        String defaultGroup = env.getProperty("spring.cloud.nacos.config.group", "DEFAULT_GROUP");

        // ─── 第一阶段：累积 key → beanNames 和 prefix → beanNames 映射 ──
        // 中间结构：dataId → group → (key → Set<beanName>)  — 精确模式
        //          dataId → group → (prefix → Set<beanName>) — 前缀模式
        // 使用累积（merge）模式，避免多个原始条目映射到同一 (dataId, group) 时发生覆盖。
        Map<String, Map<String, Map<String, Set<String>>>> accKeys = new HashMap<>();
        Map<String, Map<String, Map<String, Set<String>>>> accPrefixes = new HashMap<>();

        for (Map.Entry<String, Map<String, Map<String, RawEntry>>> dataEntry : raw.entrySet()) {
            // 解析 dataId（空时使用默认值）
            String rawDataId = dataEntry.getKey();
            String dataId = resolveWithFallback(rawDataId, defaultDataId, env);

            for (Map.Entry<String, Map<String, RawEntry>> groupEntry : dataEntry.getValue().entrySet()) {
                // 解析 group（空时使用默认值）
                String rawGroup = groupEntry.getKey();
                String group = resolveWithFallback(rawGroup, defaultGroup, env);

                // 获取或创建该 (dataId, group) 下的映射
                Map<String, Set<String>> keyToBeanNames = accKeys
                        .computeIfAbsent(dataId, k -> new HashMap<>())
                        .computeIfAbsent(group, k -> new HashMap<>());
                Map<String, Set<String>> prefixToBeanNames = accPrefixes
                        .computeIfAbsent(dataId, k -> new HashMap<>())
                        .computeIfAbsent(group, k -> new HashMap<>());

                // 遍历每个 Bean，将其 keys / prefix 累积到对应映射中
                for (Map.Entry<String, RawEntry> beanEntry : groupEntry.getValue().entrySet()) {
                    String beanName = beanEntry.getKey();
                    RawEntry entry = beanEntry.getValue();

                    // 精确模式：累积 keys
                    for (String rawKey : entry.keys) {
                        // 解析 key 中的占位符
                        String resolvedKey = env.resolvePlaceholders(rawKey);
                        if (!env.containsProperty(resolvedKey)) {
                            // key 不存在于当前环境，发出警告但继续注册
                            // （可能后续通过 Nacos 配置引入）
                            log.warn("Key '{}' (resolved from raw '{}') not found in current " +
                                    "Environment. Bean '{}' will still be registered for " +
                                    "conditional refresh; the key may be introduced later via " +
                                    "Nacos configuration.", resolvedKey, rawKey, beanName);
                        }
                        keyToBeanNames
                                .computeIfAbsent(resolvedKey, k -> new HashSet<>())
                                .add(beanName);
                    }

                    // 前缀模式：累积 prefix
                    if (!entry.prefix.isEmpty()) {
                        String resolvedPrefix = env.resolvePlaceholders(entry.prefix);
                        prefixToBeanNames
                                .computeIfAbsent(resolvedPrefix, k -> new HashSet<>())
                                .add(beanName);
                    }
                }
            }
        }

        // ─── 第二阶段：将累积结果转为不可变 IndexEntry ──────────────────
        Map<String, Map<String, IndexEntry>> result = new HashMap<>();
        // 合并所有 (dataId, group) 键集合
        Set<String> allDataIds = new HashSet<>();
        allDataIds.addAll(accKeys.keySet());
        allDataIds.addAll(accPrefixes.keySet());
        for (String dataId : allDataIds) {
            Map<String, IndexEntry> groupMap = new HashMap<>();
            Set<String> allGroups = new HashSet<>();
            allGroups.addAll(accKeys.getOrDefault(dataId, Collections.emptyMap()).keySet());
            allGroups.addAll(accPrefixes.getOrDefault(dataId, Collections.emptyMap()).keySet());
            for (String group : allGroups) {
                Map<String, Set<String>> keys = accKeys
                        .getOrDefault(dataId, Collections.emptyMap())
                        .getOrDefault(group, Collections.emptyMap());
                Map<String, Set<String>> prefixes = accPrefixes
                        .getOrDefault(dataId, Collections.emptyMap())
                        .getOrDefault(group, Collections.emptyMap());
                groupMap.put(group, new IndexEntry(
                        Collections.unmodifiableMap(keys),
                        Collections.unmodifiableMap(prefixes)));
            }
            result.put(dataId, Collections.unmodifiableMap(groupMap));
        }

        this.committedIndex = Collections.unmodifiableMap(result);
        // 清空原始数据释放内存
        this.raw.clear();
        log.info("Conditional refresh committed index built: {} dataId(s), {} raw entries cleared.",
                result.size(), raw.size());
        return this.committedIndex;
    }

    /**
     * 获取已提交的反向索引。
     *
     * @return 构建完成的反向索引
     * @throws IllegalStateException 若尚未调用 {@link #buildCommittedIndex(Environment)}
     */
    public Map<String, Map<String, IndexEntry>> getCommittedIndex() {
        if (committedIndex == null) {
            throw new IllegalStateException(
                    "committed index not built yet. Call buildCommittedIndex() first.");
        }
        return committedIndex;
    }

    /**
     * 获取所有已注册的 Bean 名称集合（用于 fallback 全量刷新）。
     *
     * @return 所有 @RefreshOnKeys Bean 名称的不可变集合
     */
    public Set<String> getAllBeanNames() {
        Set<String> all = new HashSet<>();
        for (Map<String, Map<String, RawEntry>> groupMap : raw.values()) {
            for (Map<String, RawEntry> beanMap : groupMap.values()) {
                all.addAll(beanMap.keySet());
            }
        }
        // committedIndex 构建后 raw 已被清空，需从 committedIndex 获取
        if (committedIndex != null) {
            all.clear();
            for (Map<String, IndexEntry> groupMap : committedIndex.values()) {
                for (IndexEntry entry : groupMap.values()) {
                    for (Set<String> beans : entry.keyToBeanNames.values()) {
                        all.addAll(beans);
                    }
                    for (Set<String> beans : entry.prefixToBeanNames.values()) {
                        all.addAll(beans);
                    }
                }
            }
        }
        return Collections.unmodifiableSet(all);
    }

    // ─── 私有辅助方法 ──────────────────────────────────────────────────

    /**
     * 解析字符串，若为空则回退为默认值，否则解析占位符。
     *
     * @param value        原始值（可能为空）
     * @param defaultValue 回退默认值
     * @param env          Spring Environment（用于解析占位符）
     * @return 解析后的非空字符串
     */
    private String resolveWithFallback(String value, String defaultValue, Environment env) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return env.resolvePlaceholders(value);
    }

    // ─── 内部数据结构 ──────────────────────────────────────────────────

    /**
     * (dataId, group) 组合的不可变键。
     *
     * <p>使用 final 类是因为项目基于 JDK 11（不兼容 record），
     * 但保持不可变语义（final 字段，无 setter）。
     */
    public static final class DataGroupKey {

        private final String dataId;

        private final String group;

        /**
         * 构造 (dataId, group) 组合键。
         *
         * @param dataId Nacos Data ID
         * @param group  Nacos Group
         */
        public DataGroupKey(String dataId, String group) {
            this.dataId = dataId;
            this.group = group;
        }

        /**
         * 获取 Nacos Data ID。
         *
         * @return Data ID 字符串
         */
        public String dataId() {
            return dataId;
        }

        /**
         * 获取 Nacos Group。
         *
         * @return Group 字符串
         */
        public String group() {
            return group;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DataGroupKey that = (DataGroupKey) o;
            return Objects.equals(dataId, that.dataId) && Objects.equals(group, that.group);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataId, group);
        }

        @Override
        public String toString() {
            return "DataGroupKey{" + dataId + "," + group + "}";
        }
    }

    /**
     * 单个 (dataId, group) 下的反向索引条目。
     *
     * <p>持有两种反向索引：
     * <ul>
     *   <li>{@link #keyToBeanNames}：精确模式 — 配置 key → Bean 名称集合</li>
     *   <li>{@link #prefixToBeanNames}：前缀模式 — 配置前缀 → Bean 名称集合</li>
     * </ul>
     */
    public static final class IndexEntry {

        private final Map<String, Set<String>> keyToBeanNames;
        private final Map<String, Set<String>> prefixToBeanNames;

        /**
         * 构造仅含精确模式索引的条目。
         *
         * @param keyToBeanNames 配置 key → Bean 名称集合
         */
        public IndexEntry(Map<String, Set<String>> keyToBeanNames) {
            this(keyToBeanNames, Collections.emptyMap());
        }

        /**
         * 构造同时含精确模式索引和前缀模式索引的条目。
         *
         * @param keyToBeanNames    配置 key → Bean 名称集合（精确模式）
         * @param prefixToBeanNames 配置前缀 → Bean 名称集合（前缀模式）
         */
        public IndexEntry(Map<String, Set<String>> keyToBeanNames,
                          Map<String, Set<String>> prefixToBeanNames) {
            this.keyToBeanNames = keyToBeanNames;
            this.prefixToBeanNames = prefixToBeanNames;
        }

        /**
         * 获取精确模式反向索引映射。
         *
         * @return 配置 key → Bean 名称集合（不可变）
         */
        public Map<String, Set<String>> keyToBeanNames() {
            return keyToBeanNames;
        }

        /**
         * 获取前缀模式反向索引映射。
         *
         * @return 配置前缀 → Bean 名称集合（不可变）
         */
        public Map<String, Set<String>> prefixToBeanNames() {
            return prefixToBeanNames;
        }

        /**
         * 根据变更的 keys 快速查找受影响的 Bean 名称。
         *
         * <p>同时支持两种匹配：
         * <ul>
         *   <li><strong>精确匹配</strong>：changedKey 存在于 keyToBeanNames 中</li>
         *   <li><strong>前缀匹配</strong>：changedKey 以某个 prefix + "." 开头</li>
         * </ul>
         *
         * @param changedKeys 变更的配置键集合
         * @return 受影响的 Bean 名称集合（去重）
         */
        public Set<String> findAffectedBeans(Set<String> changedKeys) {
            Set<String> affected = new HashSet<>();
            // 精确匹配
            for (String key : changedKeys) {
                Set<String> beans = keyToBeanNames.get(key);
                if (beans != null) {
                    affected.addAll(beans);
                }
            }
            // 前缀匹配：changedKey 以 prefix + "." 开头
            if (!prefixToBeanNames.isEmpty()) {
                for (String changedKey : changedKeys) {
                    for (Map.Entry<String, Set<String>> prefixEntry : prefixToBeanNames.entrySet()) {
                        String prefix = prefixEntry.getKey();
                        if (changedKey.startsWith(prefix + ".")) {
                            affected.addAll(prefixEntry.getValue());
                        }
                    }
                }
            }
            return affected;
        }
    }
}
