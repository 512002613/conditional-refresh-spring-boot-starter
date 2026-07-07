package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件刷新双模式匹配测试 — 验证精确匹配 + 前缀匹配逻辑。
 *
 * <p>通过 MetadataCollector 构建反向索引后，直接调用 IndexEntry.findAffectedBeans()
 * 验证匹配逻辑的正确性。
 */
class ConditionalRefreshListenerRefreshEventTest {

    private MetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new RefreshOnKeysPostProcessor().getCollector();
    }

    @Test
    @DisplayName("精确模式：变更 key 精确匹配 → 返回对应 beans")
    void exactMatch_returnsCorrectBeans() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{"channel.sign.secret"});
        collector.add("bean2", "testApp", "DEFAULT_GROUP",
                new String[]{"channel.sign.token"});

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 channel.sign.secret → 只影响 bean1
        Set<String> affected = entry.findAffectedBeans(Set.of("channel.sign.secret"));
        assertTrue(affected.contains("bean1"));
        assertFalse(affected.contains("bean2"));
        assertEquals(1, affected.size());
    }

    @Test
    @DisplayName("前缀模式：变更 key 匹配 prefix → 返回对应 beans")
    void prefixMatch_returnsCorrectBeans() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{}, "channel.sign");

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 channel.sign.secret → 匹配前缀 channel.sign
        Set<String> affected = entry.findAffectedBeans(Set.of("channel.sign.secret"));
        assertTrue(affected.contains("bean1"));
        assertEquals(1, affected.size());
    }

    @Test
    @DisplayName("前缀模式：变更 key 不匹配 prefix → 不返回 beans")
    void prefixMismatch_returnsEmpty() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{}, "channel.sign");

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 channel.other.xxx → 不匹配 channel.sign
        Set<String> affected = entry.findAffectedBeans(Set.of("channel.other.xxx"));
        assertTrue(affected.isEmpty());
    }

    @Test
    @DisplayName("前缀模式：prefix 与 key 同名但不带点号后缀 → 不匹配")
    void prefixWithoutDotSuffix_noMatch() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{}, "channel.sign");

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 channel.sign（不带点号后缀）→ 不匹配（必须 prefix + "." 开头）
        Set<String> affected = entry.findAffectedBeans(Set.of("channel.sign"));
        assertTrue(affected.isEmpty());
    }

    @Test
    @DisplayName("混合模式：精确 + 前缀同时命中 → 返回所有受影响 beans")
    void mixedMode_returnsAllAffectedBeans() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{"channel.sign.secret"});
        collector.add("bean2", "testApp", "DEFAULT_GROUP",
                new String[]{}, "channel.sign");

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 channel.sign.secret → 精确匹配 bean1 + 前缀匹配 bean2
        Set<String> affected = entry.findAffectedBeans(Set.of("channel.sign.secret"));
        assertTrue(affected.contains("bean1"));
        assertTrue(affected.contains("bean2"));
        assertEquals(2, affected.size());
    }

    @Test
    @DisplayName("多个 key 变更 → 返回所有受影响 beans（去重）")
    void multipleChangedBeans_returnsDeduplicated() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{"key1", "key2"});
        collector.add("bean2", "testApp", "DEFAULT_GROUP",
                new String[]{"key2", "key3"});

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        // 变更 key1, key2, key3 → bean1 (key1,key2) + bean2 (key2,key3)
        Set<String> affected = entry.findAffectedBeans(Set.of("key1", "key2", "key3"));
        assertTrue(affected.contains("bean1"));
        assertTrue(affected.contains("bean2"));
        assertEquals(2, affected.size());
    }

    @Test
    @DisplayName("空 changedKeys → 返回空")
    void emptyChangedKeys_returnsEmpty() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{"key1"});

        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(new org.springframework.mock.env.MockEnvironment());

        MetadataCollector.IndexEntry entry = index.get("testApp").get("DEFAULT_GROUP");

        Set<String> affected = entry.findAffectedBeans(Set.of());
        assertTrue(affected.isEmpty());
    }

    @Test
    @DisplayName("getAllBeanNames 返回所有注册的 Bean")
    void getAllBeanNames_returnsAllRegisteredBeans() {
        collector.add("bean1", "testApp", "DEFAULT_GROUP",
                new String[]{"key1"});
        collector.add("bean2", "testApp", "DEFAULT_GROUP",
                new String[]{}, "prefix");

        Set<String> all = collector.getAllBeanNames();
        assertTrue(all.contains("bean1"));
        assertTrue(all.contains("bean2"));
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("EnvironmentChangeEvent 构造和 getKeys 正确")
    void environmentChangeEvent_getKeysWorks() {
        Set<String> keys = new HashSet<>();
        keys.add("key1");
        keys.add("key2");

        EnvironmentChangeEvent event = new EnvironmentChangeEvent(new Object(), keys);

        assertEquals(2, event.getKeys().size());
        assertTrue(event.getKeys().contains("key1"));
        assertTrue(event.getKeys().contains("key2"));
    }
}
