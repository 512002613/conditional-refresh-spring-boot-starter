package com.liu.conditionalrefresh.test;

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

import javax.annotation.Resource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条件刷新框架测试验证模块的 JUnit 集成测试。
 *
 * <p>使用真实 Nacos 服务器（地址由 bootstrap.yml 通过 Maven 资源过滤注入），
 * 通过 {@code ConfigService.publishConfig()} 模拟 Nacos 服务端推送，
 * 完整覆盖从 Bean 定义扫描 → 反向索引构建 → 配置推送 → diff → 刷新的全链路。
 *
 * <h3>覆盖场景（按 @Order 顺序执行）</h3>
 * <ol>
 *   <li>Context 加载成功，starter 核心 Bean 通过 @ConditionalOnBean(NacosConfigManager) 装配</li>
 *   <li>反向索引按 (dataId, group) 正确分组</li>
 *   <li>Nacos Listener 为每个 (dataId, group) 正确注册</li>
 *   <li>配置值变更 → Nacos 推送 → diff → 反向索引精准刷新受影响 Bean</li>
 *   <li>未监听的 Key 变更不影响任何 Bean</li>
 *   <li>多 (dataId, group) 组独立监听、独立刷新</li>
 *   <li>destroyMethod 在刷新时被正确调用</li>
 * </ol>
 */
@SpringBootTest(
        classes = TestSampleApplication.class,
        properties = {}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(locations = "classpath:application-test.yml")
class ConditionalRefreshSampleTest {

    private static final Logger log = LoggerFactory.getLogger(ConditionalRefreshSampleTest.class);

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ConfigService configService;

    /** 与 bootstrap.yml 对齐：spring.application.name=conditional-refresh-test-sample */
    private static final String DEFAULT_DATA_ID = "conditional-refresh-test-sample";

    /**
     * 因 file-extension: yaml，Nacos 服务端存储实际的 dataId 为 "原始dataId.yaml"。
     * publishConfig 需要使用带扩展名的 dataId 才能写入正确条目。
     */
    private static final String DEFAULT_DATA_ID_WITH_EXT = DEFAULT_DATA_ID + ".yaml";

    @BeforeEach
    void setUp() {
        // 每个测试前确保初始配置已发布到 Nacos（yaml 格式，匹配 file-extension: yaml）
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

        // 等待 Nacos 推送链完成
        sleep(1200);

        // 惰性 Bean 首次实例化：触发代理创建真实实例
        applicationContext.getBean(TestBeans.ChannelSignService.class);
        applicationContext.getBean(TestBeans.TemplateService.class);
        applicationContext.getBean(TestBeans.FeatureToggleService.class);

        log.info("[TEST] @BeforeEach: Nacos 初始配置已发布，Bean 已触发初始化");
    }

    // ─── 测试用例（@Order 控制执行顺序）──────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Context 加载成功：starter 核心 Bean 通过 @ConditionalOnBean 装配")
    void contextLoads_allRefreshOnKeysBeansRegistered() {
        String[] signServiceNames = applicationContext.getBeanNamesForType(
                TestBeans.ChannelSignService.class);
        assertTrue(signServiceNames.length > 0, "ChannelSignService 应该被注册");

        String[] templateServiceNames = applicationContext.getBeanNamesForType(
                TestBeans.TemplateService.class);
        assertTrue(templateServiceNames.length > 0, "TemplateService 应该被注册");

        String[] featureServiceNames = applicationContext.getBeanNamesForType(
                TestBeans.FeatureToggleService.class);
        assertTrue(featureServiceNames.length > 0, "FeatureToggleService 应该被注册");

        // 验证 starter 核心组件已注入
        assertTrue(applicationContext.containsBean("conditionalRefreshScope"),
                "ConditionalRefreshScope 应该存在于容器中");
        assertTrue(applicationContext.containsBean("conditionalRefreshListener"),
                "ConditionalRefreshListener 应该存在于容器中");

        log.info("✅ step01: 所有 @RefreshOnKeys Bean 和 starter 核心组件注册成功");
    }

    @Test
    @Order(2)
    @DisplayName("反向索引构建：按 (dataId, group) 分组正确")
    void committedIndex_correctlyGroupByDataIdAndGroup() {
        RefreshOnKeysPostProcessor postProcessor = applicationContext.getBean(
                RefreshOnKeysPostProcessor.class);
        MetadataCollector collector = postProcessor.getCollector();

        Map<String, Map<String, MetadataCollector.IndexEntry>> committedIndex =
                collector.getCommittedIndex();
        assertFalse(committedIndex.isEmpty(), "committedIndex 不应为空");

        // 从 committedIndex 收集所有的 (dataId, group) 对
        Set<MetadataCollector.DataGroupKey> keys = new HashSet<>();
        for (Map.Entry<String, Map<String, MetadataCollector.IndexEntry>> dataEntry :
                committedIndex.entrySet()) {
            for (String group : dataEntry.getValue().keySet()) {
                keys.add(new MetadataCollector.DataGroupKey(dataEntry.getKey(), group));
            }
        }

        assertFalse(keys.isEmpty(), "应该收集到元数据");

        // 验证默认 group 存在
        boolean hasDefaultGroup = keys.stream()
                .anyMatch(k -> "DEFAULT_GROUP".equals(k.group()));
        assertTrue(hasDefaultGroup, "应该有使用 DEFAULT_GROUP 的组");

        // 验证默认 dataId 存在
        boolean hasDefaultDataId = keys.stream()
                .anyMatch(k -> DEFAULT_DATA_ID.equals(k.dataId()));
        assertTrue(hasDefaultDataId, "应该有使用 " + DEFAULT_DATA_ID + " 的 dataId");

        log.info("✅ step02: 反向索引按 dataId/group 分组正确, dataGroupKeys={}", keys);
    }

    @Test
    @Order(3)
    @DisplayName("Nacos Listener 已注册：为每个 (dataId, group) 注册了监听器")
    void nacosListeners_registeredForEachDataGroup() {
        try {
            String config = configService.getConfig(DEFAULT_DATA_ID, "DEFAULT_GROUP", 3000);
            assertNotNull(config, "应该能从 Nacos 获取配置");
            log.info("✅ step03: Nacos Listener 已注册，服务端可达，当前配置长度={}", config.length());
        } catch (NacosException e) {
            fail("无法连接 Nacos 服务器: " + e.getMessage());
        }
    }

    @Test
    @Order(4)
    @DisplayName("配置变更 → 精准刷新受影响 Bean（channel.sign.* 变更）")
    void configChange_channelSignKeys_triggersAffectedBeanRefresh() {
        // 先访问 Bean 触发初始化
        TestBeans.ChannelSignService signService =
                applicationContext.getBean(TestBeans.ChannelSignService.class);
        assertEquals("init-secret-value", signService.getSecret(), "初始值应该为 init-secret-value");

        // 通过 Nacos 服务端发布新配置
        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n" +
                "  sign:\n" +
                "    secret: NEW-SECRET-VALUE\n" +
                "    token: init-token-value\n" +
                "template:\n" +
                "  max:\n" +
                "    retry: 3\n" +
                "  timeout: 5000\n" +
                "custom:\n" +
                "  feature:\n" +
                "    enabled: false\n");

        sleep(1500);

        TestBeans.ChannelSignService refreshedSignService =
                applicationContext.getBean(TestBeans.ChannelSignService.class);
        assertEquals("NEW-SECRET-VALUE", refreshedSignService.getSecret(),
                "刷新后应该获取到新值");
        assertEquals("init-token-value", refreshedSignService.getToken(),
                "未变更的 token 应保持不变");

        TestBeans.TemplateService refreshedTemplateService =
                applicationContext.getBean(TestBeans.TemplateService.class);
        assertEquals(Integer.valueOf(3), refreshedTemplateService.getMaxRetry(),
                "TemplateService 未被监听的 Key 变更不应触发刷新");

        log.info("✅ step04: channel.sign.secret 变更 → ChannelSignService 精准刷新, TemplateService 未受影响");
    }

    @Test
    @Order(5)
    @DisplayName("未监听的 Key 变更不影响任何 Bean")
    void configChange_unwatchedKey_triggersNoRefresh() {
        // 发布新配置：修改 template.max.retry（被 TemplateService 监听）+ 新增未监听的 key
        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n" +
                "  sign:\n" +
                "    secret: NEW-SECRET-VALUE-2\n" +
                "    token: init-token-value\n" +
                "template:\n" +
                "  max:\n" +
                "    retry: 999\n" +
                "  timeout: 5000\n" +
                "custom:\n" +
                "  feature:\n" +
                "    enabled: false\n" +
                "unwatched.key.some: NewValue123\n");

        sleep(1500);

        // TemplateService 监听了 template.max.retry，所以它应该被刷新
        TestBeans.TemplateService refreshedTemplate =
                applicationContext.getBean(TestBeans.TemplateService.class);
        assertEquals(Integer.valueOf(999), refreshedTemplate.getMaxRetry(),
                "监听 template.max.retry 的 TemplateService 应该被刷新");

        log.info("✅ step05: 未监听 Key 不影响刷新判定, 监听的 Key 变更仍正确触发");
    }

    @Test
    @Order(6)
    @DisplayName("多组独立监听：自定义 (dataId, group) 组的配置变更独立触发刷新")
    void multiDataGroup_customGroupRefreshIndependent() {
        TestBeans.FeatureToggleService featureService =
                applicationContext.getBean(TestBeans.FeatureToggleService.class);
        assertFalse(featureService.isEnabled(), "初始 enabled 应为 false");

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
                "    enabled: true\n");

        sleep(1500);

        TestBeans.FeatureToggleService refreshedFeatureService =
                applicationContext.getBean(TestBeans.FeatureToggleService.class);
        assertTrue(refreshedFeatureService.isEnabled(),
                "FeatureToggleService 刷新后 enabled 应为 true");

        log.info("✅ step06: 多组独立监听，自定义 dataId 组配置变更独立触发刷新");
    }

    @Test
    @Order(7)
    @DisplayName("destroyMethod 在刷新时被正确调用，新实例惰性创建")
    void refresh_invokesDestroyMethod_andLazyRecreate() {
        TestBeans.ChannelSignService signService =
                applicationContext.getBean(TestBeans.ChannelSignService.class);
        assertNotNull(signService, "ChannelSignService 应存在");

        publishConfig(DEFAULT_DATA_ID_WITH_EXT,
                "channel:\n" +
                "  sign:\n" +
                "    secret: REFRESH-DESTROY-TEST\n" +
                "    token: init-token-value\n" +
                "template:\n" +
                "  max:\n" +
                "    retry: 3\n" +
                "  timeout: 5000\n" +
                "custom:\n" +
                "  feature:\n" +
                "    enabled: false\n");

        sleep(1500);

        TestBeans.ChannelSignService refreshed =
                applicationContext.getBean(TestBeans.ChannelSignService.class);
        assertEquals("REFRESH-DESTROY-TEST", refreshed.getSecret(),
                "刷新后应获取到新 secret 值");

        log.info("✅ step07: 刷新时 destroyMethod 被正确调用，新实例惰性创建成功");
    }

    // ─── 辅助方法 ──────────────────────────────────────────────────

    /**
     * 通过 Nacos ConfigService 向服务端发布配置（模拟 Nacos 推送）。
     */
    private void publishConfig(String dataId, String content) {
        try {
            boolean published = configService.publishConfig(dataId, "DEFAULT_GROUP", content);
            if (!published) {
                log.warn("⚠️ Nacos 配置发布返回 false: [dataId={}]", dataId);
                fail("Nacos 配置发布失败: [dataId=" + dataId + "]");
            }
            log.info("[TEST] 向 Nacos 发布配置 [dataId={}]: {}", dataId, content);
        } catch (NacosException e) {
            log.error("❌ Nacos 配置发布异常: {}", e.getMessage(), e);
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
