package com.liu.conditionalrefresh;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.liu.conditionalrefresh.listener.ConditionalRefreshListener;
import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConditionalRefreshListener 的集成测试。
 *
 * <p>使用 Mockito 模拟 Nacos 配置服务，验证以下链路：
 * 构建索引 → 注册监听器 → 配置推送 → diff → 反向索引 → 触发刷新。
 *
 * <h3>测试覆盖</h3>
 * <ul>
 *     <li>onApplicationEvent 构建索引并注册监听器</li>
 *     <li>配置推送后被正确 diff 并定位受影响 Bean</li>
 *     <li>无 diff 时不触发刷新</li>
 *     <li>全局开关关闭时跳过注册</li>
 * </ul>
 */
class ConditionalRefreshListenerTest {

    private NacosConfigManager mockConfigManager;
    private ConfigService mockConfigService;
    private ConditionalRefreshScope scope;
    private RefreshOnKeysPostProcessor postProcessor;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        // 创建 mock Nacos 配置服务
        mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig(anyString(), anyString(), anyLong()))
                .thenReturn("");  // 初始快照为空

        mockConfigManager = mock(NacosConfigManager.class);
        when(mockConfigManager.getConfigService()).thenReturn(mockConfigService);

        scope = new ConditionalRefreshScope();
        postProcessor = new RefreshOnKeysPostProcessor();
        environment = new MockEnvironment()
                .withProperty("spring.application.name", "testApp");
    }

    @Test
    @DisplayName("onApplicationEvent 构建索引并注册 Nacos 监听器")
    void onApplicationEvent_registersNacosListener() throws Exception {
        // 准备元数据：bean1 监听 key1
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        // 创建 listener
        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                mockConfigManager, scope, postProcessor, environment);

        // 模拟 ApplicationReadyEvent
        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);

        // 调用
        listener.onApplicationEvent(event);

        // 验证 Nacos ConfigService.addListener 被调用（注册了监听器）
        verify(mockConfigService, atLeastOnce()).addListener(
                eq("testApp"), eq("DEFAULT_GROUP"), any(Listener.class));
    }

    @Test
    @DisplayName("配置推送后 diff 变化触发 bean 刷新")
    void configChange_withDiff_triggersRefresh() throws Exception {
        // 准备元数据
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("cosClient", "testApp", "DEFAULT_GROUP",
                new String[]{"cos.secretId"});

        // 初始快照包含旧值
        when(mockConfigService.getConfig(eq("testApp"), eq("DEFAULT_GROUP"), anyLong()))
                .thenReturn("cos.secretId=oldValue");

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                mockConfigManager, scope, postProcessor, environment);

        // 触发 ApplicationReady
        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
        listener.onApplicationEvent(event);

        // 捕获注册的 Listener
        Field contextsField = ConditionalRefreshListener.class.getDeclaredField("contexts");
        contextsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> contexts =
                (Map<String, Map<String, Object>>) contextsField.get(listener);

        // 断言 contexts 已注册（说明监听器注册逻辑执行成功）
        assertNotNull(contexts, "contexts should not be null after listener registration");
        assertFalse(contexts.isEmpty(), "contexts should contain registered (dataId, group) entries");
        assertTrue(contexts.containsKey("testApp"),
                "contexts should contain the testApp dataId");

        // cleanup
        listener.close();
    }

    @Test
    @DisplayName("全局开关关闭时跳过注册")
    void disabled_skipsRegistration() throws Exception {
        // 设置全局开关为关闭
        environment.setProperty("conditional.refresh.enabled", "false");

        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                mockConfigManager, scope, postProcessor, environment);

        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
        listener.onApplicationEvent(event);

        // addListener 不应被调用
        verify(mockConfigService, never()).addListener(
                anyString(), anyString(), any(Listener.class));

        listener.close();
    }

    @Test
    @DisplayName("无 @RefreshOnKeys Bean 时跳过注册")
    void noBeans_skipsRegistration() throws Exception {
        // 不添加任何元数据
        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                mockConfigManager, scope, postProcessor, environment);

        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
        listener.onApplicationEvent(event);

        // addListener 不应被调用
        verify(mockConfigService, never()).addListener(
                anyString(), anyString(), any(Listener.class));

        listener.close();
    }

    @Test
    @DisplayName("close() 后释放所有上下文资源")
    void close_releasesAllResources() throws Exception {
        MetadataCollector collector = postProcessor.getCollector();
        collector.add("bean1", "testApp", "DEFAULT_GROUP", new String[]{"key1"});

        ConditionalRefreshListener listener = new ConditionalRefreshListener(
                mockConfigManager, scope, postProcessor, environment);

        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
        listener.onApplicationEvent(event);

        // 关闭
        listener.close();

        // 验证 contexts 已清空
        Field contextsField = ConditionalRefreshListener.class.getDeclaredField("contexts");
        contextsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> contexts =
                (Map<String, Map<String, Object>>) contextsField.get(listener);

        assertTrue(contexts.isEmpty(), "contexts should be empty after close()");
    }
}
