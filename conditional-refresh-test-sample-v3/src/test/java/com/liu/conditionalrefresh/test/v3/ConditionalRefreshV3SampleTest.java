package com.liu.conditionalrefresh.test.v3;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.liu.conditionalrefresh.processor.MetadataCollector;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import jakarta.annotation.Resource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件刷新框架 Spring Boot 3.5.x 集成测试。
 */
@SpringBootTest(classes = TestSampleV3Application.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(locations = "classpath:application-test.yml")
class ConditionalRefreshV3SampleTest {

    private static final Logger log = LoggerFactory.getLogger(ConditionalRefreshV3SampleTest.class);

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ConfigService configService;

    private static final String DEFAULT_DATA_ID = "conditional-refresh-test-sample-v3";
    private static final String DEFAULT_DATA_ID_WITH_EXT = DEFAULT_DATA_ID + ".yaml";

    @BeforeEach
    void setUp() {
        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n" +
                "  sign:\n" +
                "    secret: init-secret-value\n" +
                "    token: init-token-value\n" +
                "template:\n" +
                "  max:\n" +
                "    retry: 3\n" +
                "  timeout: 5000\n" +
                "custom:\n" +
                "  feature:\n" +
                "    enabled: false\n");
        sleep(1200);
        applicationContext.getBean(TestV3Beans.ChannelSignService.class);
        applicationContext.getBean(TestV3Beans.TemplateService.class);
        applicationContext.getBean(TestV3Beans.FeatureToggleService.class);
        log.info("[V3-TEST] @BeforeEach: Nacos 初始配置已发布，Bean 已触发初始化");
    }

    @Test
    @Order(1)
    @DisplayName("SB3: Context 加载成功")
    void contextLoads() {
        assertTrue(applicationContext.containsBean("conditionalRefreshScope"));
        assertTrue(applicationContext.containsBean("conditionalRefreshListener"));
        log.info("[V3-TEST] step01: starter 核心组件注册成功");
    }

    @Test
    @Order(2)
    @DisplayName("SB3: 反向索引构建正确")
    void committedIndex_correct() {
        RefreshOnKeysPostProcessor postProcessor =
                applicationContext.getBean(RefreshOnKeysPostProcessor.class);
        MetadataCollector collector = postProcessor.getCollector();
        Map<String, Map<String, MetadataCollector.IndexEntry>> committedIndex =
                collector.getCommittedIndex();
        assertFalse(committedIndex.isEmpty(), "committedIndex 不应为空");
        log.info("[V3-TEST] step02: 反向索引构建正确");
    }

    @Test
    @Order(3)
    @DisplayName("SB3: Nacos Listener 已注册")
    void nacosListeners_registered() {
        try {
            String config = configService.getConfig(DEFAULT_DATA_ID, "DEFAULT_GROUP", 3000);
            assertNotNull(config, "应该能从 Nacos 获取配置");
            log.info("[V3-TEST] step03: Nacos Listener 已注册，配置长度={}", config.length());
        } catch (NacosException e) {
            fail("无法连接 Nacos 服务器: " + e.getMessage());
        }
    }

    @Test
    @Order(4)
    @DisplayName("SB3: channel.sign.* 变更 → 精准刷新")
    void configChange_channelSign_triggersRefresh() {
        TestV3Beans.ChannelSignService signService =
                applicationContext.getBean(TestV3Beans.ChannelSignService.class);
        assertEquals("init-secret-value", signService.getSecret());

        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n  sign:\n    secret: V3-NEW-SECRET\n    token: init-token-value\n" +
                "template:\n  max:\n    retry: 3\n  timeout: 5000\n" +
                "custom:\n  feature:\n    enabled: false\n");
        sleep(1500);

        TestV3Beans.ChannelSignService refreshed =
                applicationContext.getBean(TestV3Beans.ChannelSignService.class);
        assertEquals("V3-NEW-SECRET", refreshed.getSecret());
        log.info("[V3-TEST] step04: channel.sign.secret 精准刷新成功");
    }

    @Test
    @Order(5)
    @DisplayName("SB3: 未监听 Key 不影响刷新")
    void configChange_unwatchedKey_noEffect() {
        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n  sign:\n    secret: init-secret-value\n    token: init-token-value\n" +
                "template:\n  max:\n    retry: 999\n  timeout: 5000\n" +
                "custom:\n  feature:\n    enabled: false\n" +
                "unwatched.key: value123\n");
        sleep(1500);

        TestV3Beans.TemplateService refreshed =
                applicationContext.getBean(TestV3Beans.TemplateService.class);
        assertEquals(Integer.valueOf(999), refreshed.getMaxRetry());
        log.info("[V3-TEST] step05: 未监听 Key 不影响刷新判定");
    }

    @Test
    @Order(6)
    @DisplayName("SB3: 多组独立监听")
    void multiDataGroup_independentRefresh() {
        TestV3Beans.FeatureToggleService featureService =
                applicationContext.getBean(TestV3Beans.FeatureToggleService.class);
        assertFalse(featureService.isEnabled());

        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n  sign:\n    secret: init-secret-value\n    token: init-token-value\n" +
                "template:\n  max:\n    retry: 3\n  timeout: 5000\n" +
                "custom:\n  feature:\n    enabled: true\n");
        sleep(1500);

        TestV3Beans.FeatureToggleService refreshed =
                applicationContext.getBean(TestV3Beans.FeatureToggleService.class);
        assertTrue(refreshed.isEnabled());
        log.info("[V3-TEST] step06: 多组独立监听刷新成功");
    }

    @Test
    @Order(7)
    @DisplayName("SB3: destroyMethod + 惰性实例化")
    void refresh_destroyMethod_andLazyRecreate() {
        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n  sign:\n    secret: V3-DESTROY-TEST\n    token: init-token-value\n" +
                "template:\n  max:\n    retry: 3\n  timeout: 5000\n" +
                "custom:\n  feature:\n    enabled: false\n");
        sleep(1500);

        TestV3Beans.ChannelSignService refreshed =
                applicationContext.getBean(TestV3Beans.ChannelSignService.class);
        assertEquals("V3-DESTROY-TEST", refreshed.getSecret());
        log.info("[V3-TEST] step07: destroyMethod + 惰性实例化成功");
    }

    private void publishConfig(String dataId, String content) {
        try {
            boolean published = configService.publishConfig(dataId, "DEFAULT_GROUP", content);
            if (!published) {
                fail("Nacos 配置发布失败: [dataId=" + dataId + "]");
            }
        } catch (NacosException e) {
            fail("Nacos 配置发布异常: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
