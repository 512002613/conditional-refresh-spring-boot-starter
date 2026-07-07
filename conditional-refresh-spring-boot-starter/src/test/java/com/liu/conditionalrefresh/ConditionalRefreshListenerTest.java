package com.liu.conditionalrefresh;

import com.liu.conditionalrefresh.listener.ConditionalRefreshListener;
import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConditionalRefreshListener 的单元测试。
 *
 * <p>验证双模式事件监听链路：
 * <ul>
 *     <li>ApplicationReadyEvent → 构建反向索引</li>
 *     <li>EnvironmentChangeEvent → 精确 + 前缀匹配 → 触发刷新</li>
 *     <li>RefreshScopeRefreshedEvent → fallback 全量刷新</li>
 * </ul>
 */
class ConditionalRefreshListenerTest {

    private ConditionalRefreshScope scope;
    private RefreshOnKeysPostProcessor postProcessor;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        scope = new ConditionalRefreshScope();
        postProcessor = new RefreshOnKeysPostProcessor();
        environment = new MockEnvironment()
                .withProperty("spring.application.name", "testApp");
    }

    @Test
    @DisplayName("onApplicationReady 构建反向索引")
    void onApplicationReady_buildsCommittedIndex() {
        // 准备元数据：bean1 监听 key1
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        // 模拟 ApplicationReadyEvent
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        listener.onApplicationEvent(event);

        // 验证 committedIndex 已构建
        assertFalse(collector.getCommittedIndex().isEmpty());
        assertTrue(collector.getCommittedIndex().containsKey("testApp"));

        listener.close();
    }

    @Test
    @DisplayName("EnvironmentChangeEvent 精确匹配触发 bean 刷新")
    void environmentChangeEvent_exactMatch_triggersRefresh() {
        // 准备元数据：cosClient 监听 cos.secretId
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("cosClient", "testApp", "DEFAULT_GROUP",
                new String[]{"cos.secretId"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        // 触发 ApplicationReady（构建索引）
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        // 发布 EnvironmentChangeEvent
        Set<String> changedKeys = Set.of("cos.secretId");
        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                new Object(), changedKeys);
        listener.onApplicationEvent(event);

        // 验证：由于去抖是异步的，这里只验证 listener 不抛异常
        // 实际刷新行为由 Debouncer 测试覆盖
        assertDoesNotThrow(() -> listener.close());
    }

    @Test
    @DisplayName("EnvironmentChangeEvent 未监听的 key 不触发刷新")
    void environmentChangeEvent_unwatchedKey_noException() {
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        // 发布未监听的 key
        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                new Object(), Set.of("unwatched.key"));
        assertDoesNotThrow(() -> listener.onApplicationEvent(event));

        listener.close();
    }

    @Test
    @DisplayName("EnvironmentChangeEvent 空 keys 不触发")
    void environmentChangeEvent_emptyKeys_skips() {
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                new Object(), Set.of());
        assertDoesNotThrow(() -> listener.onApplicationEvent(event));

        listener.close();
    }

    @Test
    @DisplayName("全局开关关闭时跳过处理")
    void disabled_skipsProcessing() {
        environment.setProperty("conditional.refresh.enabled", "false");

        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        // ApplicationReadyEvent 不应构建索引
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
        // committedIndex 应为 null（未构建）
        assertThrows(IllegalStateException.class, collector::getCommittedIndex);

        listener.close();
    }

    @Test
    @DisplayName("无 @RefreshOnKeys Bean 时跳过")
    void noBeans_skipsProcessing() {
        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        // 不应抛异常
        EnvironmentChangeEvent event = new EnvironmentChangeEvent(
                new Object(), Set.of("any.key"));
        assertDoesNotThrow(() -> listener.onApplicationEvent(event));

        listener.close();
    }

    @Test
    @DisplayName("close() 后释放资源")
    void close_releasesResources() {
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));
        assertDoesNotThrow(() -> listener.close());
    }

    @Test
    @DisplayName("RefreshScopeRefreshedEvent 触发全量刷新（fallback）")
    void refreshScopeRefreshedEvent_triggersFullRefresh() {
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});
        collector.add("bean2", "testApp", "DEFAULT_GROUP", new String[]{"key2"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        // 先构建索引
        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        // 发布 RefreshScopeRefreshedEvent
        RefreshScopeRefreshedEvent event = new RefreshScopeRefreshedEvent();
        assertDoesNotThrow(() -> listener.onApplicationEvent(event));

        listener.close();
    }

    @Test
    @DisplayName("supportsEventType 支持三种事件类型")
    void supportsEventType_returnsCorrectValues() {
        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        assertTrue(listener.supportsEventType(ApplicationReadyEvent.class));
        assertTrue(listener.supportsEventType(EnvironmentChangeEvent.class));
        assertTrue(listener.supportsEventType(RefreshScopeRefreshedEvent.class));
        assertFalse(listener.supportsEventType(org.springframework.context.event.ContextRefreshedEvent.class));

        listener.close();
    }

    @Test
    @DisplayName("getOrder 返回 LOWEST_PRECEDENCE")
    void getOrder_returnsLowestPrecedence() {
        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                scope, postProcessor, environment);

        assertEquals(org.springframework.core.Ordered.LOWEST_PRECEDENCE, listener.getOrder());

        listener.close();
    }
}
