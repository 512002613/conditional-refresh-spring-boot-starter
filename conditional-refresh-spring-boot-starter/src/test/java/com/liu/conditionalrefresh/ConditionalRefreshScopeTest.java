package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConditionalRefreshScope 的单元测试。
 *
 * <h3>测试范围</h3>
 * <ul>
 *     <li>正常刷新：旧实例被销毁，返回 {@code true}</li>
 *     <li>惰性重建：销毁后新实例尚未创建，下次访问触发 {@link org.springframework.beans.factory.ObjectFactory}</li>
 *     <li>不存在的 Bean：返回 {@code true}（配置变更下次访问生效）</li>
 *     <li>空名称：抛出 {@link IllegalArgumentException}</li>
 *     <li>连续刷新：第一次销毁旧实例，第二次仍返回 {@code true}</li>
 * </ul>
 */
class ConditionalRefreshScopeTest {

    private ConditionalRefreshScope scope;

    @BeforeEach
    void setUp() {
        scope = new ConditionalRefreshScope();
        // 清理 survivor cache，避免测试间相互污染
        ConditionalRefreshScope.survivorCacheClear();
    }

    @Test
    @DisplayName("getName 返回 'conditionalRefresh'")
    void getName_returnsScopeName() {
        assertEquals("conditionalRefresh", scope.getName());
    }

    @Test
    @DisplayName("SCOPE_NAME 常量为 'conditionalRefresh'")
    void scopeName_constantValue() {
        assertEquals("conditionalRefresh", ConditionalRefreshScope.SCOPE_NAME);
    }

    @Test
    @DisplayName("刷新不存在的 bean 返回 true（配置变更下次访问生效）")
    void refresh_nonExistentBean_returnsTrue() {
        // 语义：无缓存实例时，无需销毁，配置变更在下一次 proxy 访问时自动生效
        boolean result = scope.refresh("nonExistentBean");
        assertTrue(result, "Should return true — config change will take effect on next proxy access");
    }

    @Test
    @DisplayName("刷新 null 名称抛出 IllegalArgumentException")
    void refresh_nullName_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> scope.refresh(null));
    }

    @Test
    @DisplayName("刷新空字符串名称抛出 IllegalArgumentException")
    void refresh_emptyName_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> scope.refresh(""));
    }

    @Test
    @DisplayName("刷新已存在的 bean 返回 true；再次刷新也返回 true（无缓存实例语义）")
    void refresh_existingBean_thenNotExisting_returnsTrue() {
        // 使用 ObjectFactory 注册一个 bean 到 scope 缓存
        // 真实场景中 scoped-proxy 使用 "scopedTarget." + beanName 作为缓存 key，
        // 测试中需模拟此行为以与 refresh() 内部逻辑一致。
        String beanName = "testBean";
        String targetName = "scopedTarget." + beanName;
        AtomicInteger createCount = new AtomicInteger(0);

        // 通过 get 触发 ObjectFactory 创建并缓存 bean
        scope.get(targetName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });

        // 确认已创建
        assertEquals(1, createCount.get(), "Bean should be created on first get()");

        // 第一次刷新 — 销毁旧实例，返回 true
        boolean firstRefresh = scope.refresh(beanName);
        assertTrue(firstRefresh, "Should return true when old instance was destroyed");

        // 第二次刷新 — 缓存已空，但仍返回 true（配置变更下次 proxy 访问生效）
        boolean secondRefresh = scope.refresh(beanName);
        assertTrue(secondRefresh,
                "Should return true even when no cached instance — config change is still effective");
    }

    @Test
    @DisplayName("刷新后再次 get 触发惰性重建")
    void refresh_thenGet_triggersLazyRecreation() {
        // 真实场景中 scoped-proxy 使用 "scopedTarget." + beanName 作为缓存 key，
        // 测试中需模拟此行为以与 refresh() 内部逻辑一致。
        String beanName = "lazyBean";
        String targetName = "scopedTarget." + beanName;
        AtomicInteger createCount = new AtomicInteger(0);

        // 首次创建
        scope.get(targetName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });
        assertEquals(1, createCount.get());

        // 刷新销毁
        scope.refresh(beanName);

        // 再次 get — 应触发重新创建
        Object newInstance = scope.get(targetName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });
        assertNotNull(newInstance, "New instance should be created lazily on next access");
        assertEquals(2, createCount.get(), "ObjectFactory should be invoked again after refresh");
    }
}
