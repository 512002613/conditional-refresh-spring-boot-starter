package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.processor.MetadataCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetadataCollector 的单元测试。
 */
class MetadataCollectorTest {

    private MetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetadataCollector();
    }

    @Test
    @DisplayName("空收集器 isEmpty 为 true")
    void isEmpty_empty_returnsTrue() {
        assertTrue(collector.isEmpty());
    }

    @Test
    @DisplayName("添加元数据后 isEmpty 为 false")
    void isEmpty_afterAdd_returnsFalse() {
        collector.add("bean1", "dataId1", "group1", new String[]{"key1", "key2"});
        assertFalse(collector.isEmpty());
    }

    @Test
    @DisplayName("空 rawKey 不增加元数据")
    void add_emptyKeys_skipped() {
        collector.add("bean1", "dataId1", "group1", new String[]{});
        assertTrue(collector.isEmpty());
    }

    @Test
    @DisplayName("构建反向索引：key 映射到 bean 集合")
    void buildCommittedIndex_keyMappedToBeans() {
        collector.add("bean1", "dataId1", "group1", new String[]{"key1", "key2"});
        collector.add("bean2", "dataId1", "group1", new String[]{"key2", "key3"});

        MockEnvironment env = new MockEnvironment();
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(env);

        // key2 同时被 bean1 和 bean2 监听
        MetadataCollector.IndexEntry entry = index.get("dataId1").get("group1");
        Set<String> beansForKey2 = entry.keyToBeanNames().get("key2");
        assertNotNull(beansForKey2);
        assertTrue(beansForKey2.contains("bean1"));
        assertTrue(beansForKey2.contains("bean2"));
    }

    @Test
    @DisplayName("空 group 回退为 DEFAULT_GROUP")
    void buildCommittedIndex_emptyGroup_fallbackDefault() {
        collector.add("bean1", "dataId1", "", new String[]{"key1"});

        MockEnvironment env = new MockEnvironment();
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(env);

        assertNotNull(index.get("dataId1").get("DEFAULT_GROUP"));
    }

    @Test
    @DisplayName("空 dataId 回退为 spring.application.name")
    void buildCommittedIndex_emptyDataId_fallbackAppName() {
        collector.add("bean1", "", "group1", new String[]{"key1"});

        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "myapp");
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(env);

        assertNotNull(index.get("myapp").get("group1"));
    }

    @Test
    @DisplayName("占位符被正确解析")
    void buildCommittedIndex_placeholderResolved() {
        collector.add("bean1", "${custom.dataid}", "group1",
                new String[]{"${custom.key}"});

        MockEnvironment env = new MockEnvironment()
                .withProperty("custom.dataid", "real-dataid")
                .withProperty("custom.key", "real-key");
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(env);

        assertNotNull(index.get("real-dataid").get("group1"));
        Set<String> beans = index.get("real-dataid").get("group1")
                .keyToBeanNames().get("real-key");
        assertNotNull(beans);
        assertTrue(beans.contains("bean1"));
    }

    @Test
    @DisplayName("IndexEntry.findAffectedBeans 正确匹配")
    void findAffectedBeans_correctMatch() {
        collector.add("bean1", "dataId1", "group1", new String[]{"key1"});
        collector.add("bean2", "dataId1", "group1", new String[]{"key2"});

        MockEnvironment env = new MockEnvironment();
        Map<String, Map<String, MetadataCollector.IndexEntry>> index =
                collector.buildCommittedIndex(env);

        MetadataCollector.IndexEntry entry = index.get("dataId1").get("group1");
        Set<String> affected = entry.findAffectedBeans(toSet("key1", "key2"));
        assertTrue(affected.contains("bean1"));
        assertTrue(affected.contains("bean2"));

        // 只有 key1 变更时只影响 bean1
        Set<String> onlyKey1 = entry.findAffectedBeans(Collections.singleton("key1"));
        assertTrue(onlyKey1.contains("bean1"));
        assertFalse(onlyKey1.contains("bean2"));
    }

    @Test
    @DisplayName("重复构建抛出异常")
    void buildCommittedIndex_doubleBuild_throws() {
        collector.add("bean1", "dataId1", "group1", new String[]{"key1"});

        MockEnvironment env = new MockEnvironment();
        collector.buildCommittedIndex(env);
        assertThrows(IllegalStateException.class, () ->
                collector.buildCommittedIndex(env));
    }

    /**
     * 辅助方法：创建包含指定元素的 Set（Java 8 兼容）。
     */
    private static Set<String> toSet(String... elements) {
        return new HashSet<>(Arrays.asList(elements));
    }
}
