package com.liu.conditionalrefresh.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * 配置解析与差异比较工具类。
 *
 * <p>提供将 Nacos 推送的配置文本解析为结构化 {@code Map<String, Object>}，
 * 并比较两个快照之间 <strong>值真正发生变化</strong> 的 Key 集合。
 *
 * <h3>支持的配置格式</h3>
 * <ul>
 *     <li>Properties 格式（{@code key=value}）</li>
 *     <li>YAML 格式（支持 {@code spring.config.import: "nacos:"} 引入的 yaml 配置）</li>
 * </ul>
 *
 * <h3>Diff 语义</h3>
 * <ul>
 *     <li><strong>新增</strong>：旧快照中没有的 Key → 视为变更。</li>
 *     <li><strong>修改</strong>：两个快照都有但值不同 → 视为变更。</li>
 *     <li><strong>删除</strong>：旧快照有但新快照没有 → <em>不视为变更</em>
 *         （Bean 通常不会监听被删除的配置，减少无效刷新）。</li>
 *     <li><strong>不变</strong>：值相同 → 不触发刷新。</li>
 * </ul>
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
public final class ConfigDiffUtils {

    private static final Logger log = LoggerFactory.getLogger(ConfigDiffUtils.class);

    private ConfigDiffUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 解析配置文本为扁平化的 {@code Map<String, Key, Value>}。
     *
     * <p>根据格式自动选择解析器：
     * <ul>
     *     <li>若内容缩进/冒号特征明显，使用 YAML 解析器。</li>
     *     <li>否则使用 Properties 解析器。</li>
     * </ul>
     *
     * @param configText Nacos 推送的配置全文
     * @return 扁平化的配置键值对（空配置返回空 Map）
     */
    public static Map<String, Object> parse(String configText) {
        if (configText == null || configText.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        // 简单启发式：含 ":" 且不是等号风格则尝试 YAML
        if (isYamlStyle(configText)) {
            return parseYaml(configText);
        }
        return parseProperties(configText);
    }

    /**
     * 比较两个快照，返回值发生变化的键集合。
     *
     * @param oldSnapshot 上次的配置快照
     * @param newSnapshot 当前的配置快照
     * @return 变更的键集合（无变更时返回空集合）
     */
    public static Set<String> diff(Map<String, Object> oldSnapshot,
                                   Map<String, Object> newSnapshot) {
        Set<String> changed = new HashSet<>();

        // 检查新增和修改
        for (Map.Entry<String, Object> entry : newSnapshot.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Object oldValue = oldSnapshot.get(key);

            if (oldValue == null && !oldSnapshot.containsKey(key)) {
                // 新增键
                changed.add(key);
            } else if (!Objects.equals(oldValue, newValue)) {
                // 值发生变化
                changed.add(key);
            }
        }

        // 注意：旧键被删除时不视为变更，避免无谓刷新

        return changed;
    }

    /**
     * 将配置文本解析为扁平化 Map（Properties 风格）。
     *
     * @param configText Properties 格式的配置文本
     * @return 扁平化的键值 Map
     */
    private static Map<String, Object> parseProperties(String configText) {
        java.util.Properties props = new java.util.Properties();
        try (StringReader reader = new StringReader(configText)) {
            props.load(reader);
        } catch (IOException e) {
            // Properties 不会在 StringReader 上抛 IOException，但需声明
            log.warn("Unexpected IOException while parsing Properties config: {}", e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, Object> result = new HashMap<>();
        props.forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }

    /**
     * 将配置文本解析为扁平化 Map（YAML 风格）。
     *
     * <p>若 YAML 解析失败，会回退为 Properties 解析方式并记录告警日志。
     *
     * @param configText YAML 格式的配置文本
     * @return 扁平化的键值 Map
     */
    private static Map<String, Object> parseYaml(String configText) {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            PropertySource<?> propertySource = loader.load(
                    "conditional-refresh-yaml",
                    new ByteArrayResource(configText.getBytes())).get(0);
            Map<String, Object> map = new HashMap<>();
            if (propertySource.getSource() instanceof Map) {
                // 安全强转：PropertySourceLoader 保证 getSource() 返回 Map
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) propertySource.getSource();
                map.putAll(typed);
            } else {
                map.put(propertySource.getName(), propertySource.getSource());
            }
            return flattenMap(map, "");
        } catch (Exception e) {
            // YAML 解析失败，回退为 Properties
            log.warn("YAML parsing failed, falling back to Properties parsing. Cause: {}",
                    e.getMessage());
            return parseProperties(configText);
        }
    }

    /**
     * 递归扁平化嵌套 Map，将 nested.key 转为扁平格式。
     *
     * @param nestedMap 嵌套 Map
     * @param prefix    当前前缀
     * @return 扁平化的 Map
     */
    private static Map<String, Object> flattenMap(Map<String, Object> nestedMap,
                                                  String prefix) {
        Map<String, Object> flat = new HashMap<>();
        for (Map.Entry<String, Object> entry : nestedMap.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flat.putAll(flattenMap((Map<String, Object>) value, key));
            } else {
                flat.put(key, value);
            }
        }
        return flat;
    }

    /**
     * 简单启发式判断是否为 YAML 格式。
     *
     * <p>若行中有大量缩进 + 冒号结构则认为 YAML，否则 Properties。
     *
     * @param text 配置文本内容
     * @return 若判定为 YAML 格式返回 {@code true}
     */
    private static boolean isYamlStyle(String text) {
        // 简单判断：如果含代表性 YAML 结构（"key: value"，无等号）
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            if (trimmed.contains(":") && !trimmed.startsWith("[")) {
                return true;
            }
        }
        return false;
    }
}
