package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件刷新作用域在 context restart 场景下的生存性测试。
 *
 * <p>验证当 Spring 上下文重启时（旧 scope 被 destroy、新 scope 被创建），
 * {@link ConditionalRefreshScope} 仍能通过 survivor cache 正确刷新 Bean。
 */
class ConditionalRefreshScopeSurvivalTest {

    private ConditionalRefreshScope scope;

    /** 通过反射读取 SURVIVOR_CACHE 用于断言。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getSurvivorCache() throws Exception {
        Field f = ConditionalRefreshScope.class.getDeclaredField("SURVIVOR_CACHE");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    @BeforeEach
    void setUp() throws Exception {
        scope = new ConditionalRefreshScope();
        // 确保每个测试从干净的 survivor cache 开始
        getSurvivorCache().clear();
    }

    @Test
    @DisplayName("destroy 后 survivor cache 应包含旧缓存的 Bean 实例")
    void destroy_snapshotsCacheToSurvivor() throws Exception {
        String beanName = "surviveBean";
        Object instance = new Object();

        // 创建 scope，模拟 Bean 被 get() 创建
        scope.get(beanName, () -> instance);
        assertEquals(0, getSurvivorCache().size(), "destroy 前 survivor cache 应为空");

        // 模拟 context restart：旧 scope 被 destroy
        scope.destroy();

        // 验证 survivor cache 中出现了快照
        Map<String, Object> survivor = getSurvivorCache();
        assertEquals(1, survivor.size());
        assertSame(instance, survivor.get(beanName),
                "survivor cache 应保存旧缓存中的 Bean 实例");
    }

    @Test
    @DisplayName("新 scope 实例通过 survivor cache 刷新成功（模拟 context restart）")
    void newScope_refresh_canRecoverFromSurvivorCache() throws Exception {
        String beanName = "restartBean";
        Object original = new Object();

        // 旧 scope：创建 Bean
        scope.get(beanName, () -> original);

        // 模拟 context restart：旧 scope destroy → 新 scope 创建
        scope.destroy();
        ConditionalRefreshScope newScope = new ConditionalRefreshScope();

        // 新 scope 的 cache 是空的，但 survivor cache 有数据
        assertTrue(getSurvivorCache().containsKey(beanName));

        // 刷新应成功并返回 true
        boolean refreshed = newScope.refresh(beanName);
        assertTrue(refreshed,
                "新 scope 应通过 survivor cache 恢复'旧实例已销毁'的语义");
    }

    @Test
    @DisplayName("从 survivor cache 刷新后 cache 应被清理（避免泄漏）")
    void refreshFromSurvivor_clearsEntryFromCache() throws Exception {
        String beanName = "cleanupBean";

        scope.get(beanName, () -> new Object());
        scope.destroy();
        ConditionalRefreshScope newScope = new ConditionalRefreshScope();

        assertTrue(getSurvivorCache().containsKey(beanName));

        newScope.refresh(beanName);

        assertFalse(getSurvivorCache().containsKey(beanName),
                "refresh 后应从 survivor cache 中移除对应条目");
    }

    @Test
    @DisplayName("连续两次刷新：第一次从 survivor cache 返回 true，第二次仍返回 true（无缓存实例语义）")
    void doubleRefresh_secondReturnsTrue() throws Exception {
        String beanName = "doubleBean";

        scope.get(beanName, () -> new Object());
        scope.destroy();
        ConditionalRefreshScope newScope = new ConditionalRefreshScope();

        // 第一次：survivor cache 命中，返回 true
        assertTrue(newScope.refresh(beanName));

        // 第二次：两个 cache 都为空，但语义为"配置变更已生效"，仍返回 true
        assertTrue(newScope.refresh(beanName),
                "第二次刷新时虽无缓存实例，但配置变更仍有效，应返回 true");
    }

    @Test
    @DisplayName("survivorCacheSize() 反映暂存数量")
    void survivorCacheSize_reflectsStoredCount() {
        assertEquals(0, ConditionalRefreshScope.survivorCacheSize());

        scope.get("b1", Object::new);
        scope.get("b2", Object::new);
        scope.destroy();

        assertEquals(2, ConditionalRefreshScope.survivorCacheSize());
    }

    @Test
    @DisplayName("destroy 空缓存不产生异常")
    void destroy_emptyCache_noException() {
        assertDoesNotThrow(() -> scope.destroy());
        assertEquals(0, ConditionalRefreshScope.survivorCacheSize());
    }

    @Test
    @DisplayName("多 Bean 全部被快照")
    void destroy_multipleBeans_allSnapshotted() throws Exception {
        AtomicInteger createCount = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            scope.get("bean_" + i, () -> {
                createCount.incrementAndGet();
                return "instance_" + idx;
            });
        }

        scope.destroy();

        Map<String, Object> survivor = getSurvivorCache();
        assertEquals(5, survivor.size());
        assertEquals("instance_0", survivor.get("bean_0"));
        assertEquals("instance_4", survivor.get("bean_4"));
    }
}
