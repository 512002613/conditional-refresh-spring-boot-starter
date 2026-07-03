package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.listener.ConfigDiffUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigDiffUtils 的单元测试。
 */
class ConfigDiffUtilsTest {

    @Test
    @DisplayName("空配置文本返回空 Map")
    void parse_emptyText_returnsEmptyMap() {
        Map<String, Object> result = ConfigDiffUtils.parse("");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("null 配置文本返回空 Map")
    void parse_nullText_returnsEmptyMap() {
        Map<String, Object> result = ConfigDiffUtils.parse(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("空白配置文本返回空 Map")
    void parse_blankText_returnsEmptyMap() {
        Map<String, Object> result = ConfigDiffUtils.parse("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("解析 properties 格式")
    void parse_propertiesFormat_success() {
        String text = "key1=value1\nkey2=value2";
        Map<String, Object> result = ConfigDiffUtils.parse(text);
        assertEquals("value1", result.get("key1"));
        assertEquals("value2", result.get("key2"));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("diff：相同快照无变更")
    void diff_sameSnapshot_noChanges() {
        Map<String, Object> snap = Collections.unmodifiableMap(createMap("a", "1", "b", "2"));
        Set<String> changed = ConfigDiffUtils.diff(snap, snap);
        assertTrue(changed.isEmpty());
    }

    @Test
    @DisplayName("diff：新增 key 视为变更")
    void diff_newKey_returnsNewKey() {
        Map<String, Object> old = Collections.singletonMap("a", "1");
        Map<String, Object> neu = createMap("a", "1", "b", "2");
        Set<String> changed = ConfigDiffUtils.diff(old, neu);
        assertTrue(changed.contains("b"));
        assertFalse(changed.contains("a"));
    }

    @Test
    @DisplayName("diff：修改 key 视为变更")
    void diff_modifiedKey_returnsModifiedKey() {
        Map<String, Object> old = createMap("a", "1", "b", "2");
        Map<String, Object> neu = createMap("a", "1", "b", "3");
        Set<String> changed = ConfigDiffUtils.diff(old, neu);
        assertTrue(changed.contains("b"));
        assertFalse(changed.contains("a"));
    }

    @Test
    @DisplayName("diff：删除 key 不视为变更")
    void diff_deletedKey_noChange() {
        Map<String, Object> old = createMap("a", "1", "b", "2");
        Map<String, Object> neu = Collections.singletonMap("a", "1");
        Set<String> changed = ConfigDiffUtils.diff(old, neu);
        assertTrue(changed.isEmpty());
    }

    @Test
    @DisplayName("diff：首次推送（旧快照为空）所有 key 都视为变更")
    void diff_initialPush_allKeysAsChanges() {
        Map<String, Object> old = Collections.emptyMap();
        Map<String, Object> neu = createMap("a", "1", "b", "2");
        Set<String> changed = ConfigDiffUtils.diff(old, neu);
        assertEquals(2, changed.size());
    }

    @Test
    @DisplayName("Diff + parse 端到端：值相同不报变更")
    void diffParse_e2e_sameValue_noChanges() {
        String oldText = "server.port=8080";
        String newText = "server.port=8080";
        Map<String, Object> oldSnap = ConfigDiffUtils.parse(oldText);
        Map<String, Object> newSnap = ConfigDiffUtils.parse(newText);
        Set<String> changed = ConfigDiffUtils.diff(oldSnap, newSnap);
        assertTrue(changed.isEmpty());
    }

    @Test
    @DisplayName("Diff + parse 端到端：值不同报变更")
    void diffParse_e2e_differentValue_reportsChange() {
        String oldText = "server.port=8080";
        String newText = "server.port=9090";
        Map<String, Object> oldSnap = ConfigDiffUtils.parse(oldText);
        Map<String, Object> newSnap = ConfigDiffUtils.parse(newText);
        Set<String> changed = ConfigDiffUtils.diff(oldSnap, newSnap);
        assertEquals(1, changed.size());
        assertTrue(changed.contains("server.port"));
    }

    /**
     * 辅助方法：创建包含两个键值对的 Map（Java 8 兼容）。
     */
    private static Map<String, Object> createMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
