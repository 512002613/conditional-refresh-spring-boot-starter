package com.liu.conditionalrefresh.test.v2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件刷新框架 V2 测试验证模块的集成测试。
 *
 * <p>验证 Spring Boot 3.0.x + Spring Cloud 2022.0.x 环境下：
 * <ul>
 *     <li>应用上下文正常加载</li>
 *     <li>条件刷新 Bean 正确初始化</li>
 *     <li>条件刷新 Bean 可被正常访问</li>
 * </ul>
 *
 * <p>注意：需要真实 Nacos 服务器才能运行。如无 Nacos 环境，
 * 测试会因连接失败而报错，这是预期行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConditionalRefreshV2SampleTest {

    @Autowired(required = false)
    private TestV2Beans.ChannelSignService channelSignService;

    @Autowired(required = false)
    private TestV2Beans.TemplateService templateService;

    @Autowired(required = false)
    private TestV2Beans.FeatureToggleService featureToggleService;

    @Test
    @DisplayName("V2: 应用上下文正常加载")
    void contextLoads() {
        // 如果上下文加载成功，此测试即通过
        assertTrue(true, "Spring context loaded successfully");
    }

    @Test
    @DisplayName("V2: ChannelSignService Bean 已创建")
    void channelSignService_beanExists() {
        assertNotNull(channelSignService, "ChannelSignService should be initialized");
    }

    @Test
    @DisplayName("V2: TemplateService Bean 已创建")
    void templateService_beanExists() {
        assertNotNull(templateService, "TemplateService should be initialized");
    }

    @Test
    @DisplayName("V2: FeatureToggleService Bean 已创建")
    void featureToggleService_beanExists() {
        assertNotNull(featureToggleService, "FeatureToggleService should be initialized");
    }

    @Test
    @DisplayName("V2: FeatureToggleService 默认值为 false")
    void featureToggleService_defaultValue() {
        assertNotNull(featureToggleService);
        assertFalse(featureToggleService.isEnabled(),
                "FeatureToggleService should default to disabled");
    }
}
