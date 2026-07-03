package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.listener.ListenerContext;
import com.liu.conditionalrefresh.processor.MetadataCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ListenerContext 的单元测试 — 验证委托行为和不可变性。
 */
class ListenerContextTest {

    private MetadataCollector.IndexEntry indexEntry;
    private ListenerContext context;

    @BeforeEach
    void setUp() {
        // 构建反向索引：key1 → {bean1}, key2 → {bean1, bean2}
        Map<String, Set<String>> keyToBeans = new HashMap<>();

        Set<String> key1Beans = new HashSet<>();
        key1Beans.add("bean1");
        keyToBeans.put("key1", key1Beans);

        Set<String> key2Beans = new HashSet<>();
        key2Beans.add("bean1");
        key2Beans.add("bean2");
        keyToBeans.put("key2", key2Beans);

        indexEntry = new MetadataCollector.IndexEntry(keyToBeans);
        context = new ListenerContext(indexEntry, Collections.<String, Object>emptyMap());
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("findAffectedBeans 委托给 IndexEntry，返回正确结果")
    void findAffectedBeans_delegatesToIndexEntry() {
        Set<String> affected = context.findAffectedBeans(toSet("key1", "key2"));
        assertTrue(affected.contains("bean1"));
        assertTrue(affected.contains("bean2"));
        assertEquals(2, affected.size());
    }

    @Test
    @DisplayName("findAffectedBeans：只变更 key1 仅影响 bean1")
    void findAffectedBeans_singleKeyOnlyAffectsOneBean() {
        Set<String> affected = context.findAffectedBeans(Collections.singleton("key1"));
        assertTrue(affected.contains("bean1"));
        assertFalse(affected.contains("bean2"));
        assertEquals(1, affected.size());
    }

    @Test
    @DisplayName("findAffectedBeans：未监听的 key 不影响任何 bean")
    void findAffectedBeans_unwatchedKey_returnsEmpty() {
        Set<String> affected = context.findAffectedBeans(Collections.singleton("unknownKey"));
        assertTrue(affected.isEmpty());
    }

    @Test
    @DisplayName("findAffectedBeans：空集合返回空")
    void findAffectedBeans_emptyInput_returnsEmpty() {
        Set<String> affected = context.findAffectedBeans(Collections.<String>emptySet());
        assertTrue(affected.isEmpty());
    }

    @Test
    @DisplayName("快照原子替换并返回旧值")
    void replaceSnapshotAndGetOld_returnsOldAndReplaces() {
        Map<String, Object> old = context.replaceSnapshotAndGetOld(Collections.singletonMap("a", "1"));
        assertTrue(old.isEmpty(), "Initial snapshot was empty");

        Map<String, Object> old2 = context.replaceSnapshotAndGetOld(Collections.singletonMap("b", "2"));
        assertEquals("1", old2.get("a"), "Should return previous snapshot");

        Map<String, Object> current = context.getCurrentSnapshot();
        assertEquals("2", current.get("b"), "Current snapshot should be the latest");
    }

    @Test
    @DisplayName("getKeyToBeans 返回不可变视图")
    void getKeyToBeans_returnsUnmodifiableView() {
        Map<String, Set<String>> view = context.getKeyToBeans();
        assertThrows(UnsupportedOperationException.class, () -> view.put("k", new HashSet<>()));
    }

    @Test
    @DisplayName("不存在初始快照时默认为空 Map")
    void nullInitialSnapshot_defaultsToEmpty() {
        ListenerContext ctx = new ListenerContext(indexEntry, null);
        assertNotNull(ctx.getCurrentSnapshot());
        assertTrue(ctx.getCurrentSnapshot().isEmpty());
        ctx.close();
    }

    /**
     * 辅助方法：创建包含指定元素的 Set（Java 8 兼容）。
     */
    private static Set<String> toSet(String... elements) {
        return new HashSet<>(Arrays.asList(elements));
    }
}
