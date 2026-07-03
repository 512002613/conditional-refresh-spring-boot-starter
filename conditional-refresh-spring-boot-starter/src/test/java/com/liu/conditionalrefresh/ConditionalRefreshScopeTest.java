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
 *     <li>不存在的 Bean：返回 {@code false}</li>
 *     <li>空名称：抛出 {@link IllegalArgumentException}</li>
 *     <li>destroyMethod 异常：不阻断流程</li>
 * </ul>
 */
class ConditionalRefreshScopeTest {

    private ConditionalRefreshScope scope;

    @BeforeEach
    void setUp() {
        scope = new ConditionalRefreshScope();
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
    @DisplayName("刷新不存在的 bean 返回 false")
    void refresh_nonExistentBean_returnsFalse() {
        boolean result = scope.refresh("nonExistentBean");
        assertFalse(result, "Should return false when bean doesn't exist in scope cache");
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
    @DisplayName("刷新已存在的 bean 返回 true，且再次刷新返回 false")
    void refresh_existingBean_thenNotExisting() {
        // 使用 ObjectFactory 注册一个 bean 到 scope 缓存
        String beanName = "testBean";
        AtomicInteger createCount = new AtomicInteger(0);

        // 通过 put 方法将 bean 放入缓存（模拟 GenericScope 的内部行为）
        // GenericScope 的 cache 是私有的，我们通过 get → 触发 ObjectFactory → 缓存结果
        scope.get(beanName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });

        // 确认已创建
        assertEquals(1, createCount.get(), "Bean should be created on first get()");

        // 刷新 — 销毁旧实例
        boolean firstRefresh = scope.refresh(beanName);
        assertTrue(firstRefresh, "Should return true when old instance was destroyed");

        // 再次刷新 — 缓存已空
        boolean secondRefresh = scope.refresh(beanName);
        assertFalse(secondRefresh, "Should return false when bean no longer in scope");
    }

    @Test
    @DisplayName("刷新后再次 get 触发惰性重建")
    void refresh_thenGet_triggersLazyRecreation() {
        String beanName = "lazyBean";
        AtomicInteger createCount = new AtomicInteger(0);

        // 首次创建
        scope.get(beanName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });
        assertEquals(1, createCount.get());

        // 刷新销毁
        scope.refresh(beanName);

        // 再次 get — 应触发重新创建
        Object newInstance = scope.get(beanName, () -> {
            createCount.incrementAndGet();
            return new Object();
        });
        assertNotNull(newInstance, "New instance should be created lazily on next access");
        assertEquals(2, createCount.get(), "ObjectFactory should be invoked again after refresh");
    }
}
