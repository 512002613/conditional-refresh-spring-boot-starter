package com.liu.conditionalrefresh.test.v3;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.liu.conditionalrefresh.annotation.RefreshOnKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 3.5.x 测试验证模块的示例 Bean 配置。
 *
 * <p>演示两种 {@code @RefreshOnKeys} 用法：
 * <ul>
 *     <li>精确模式：{@code @RefreshOnKeys({"channel.sign.secret", "channel.sign.token"})}</li>
 *     <li>前缀模式：{@code @RefreshOnKeys(prefix = "template")} — 注入整个 {@code @ConfigurationProperties} Bean</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({TestV3Beans.ChannelSignProperties.class,
        TestV3Beans.TemplateProperties.class, TestV3Beans.FeatureProperties.class})
public class TestV3Beans {

    private static final Logger log = LoggerFactory.getLogger(TestV3Beans.class);

    /**
     * 精确模式：监听 channel.sign.secret 和 channel.sign.token。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys({"channel.sign.secret", "channel.sign.token"})
    public ChannelSignService channelSignService(ChannelSignProperties props) {
        log.info("[V3] ChannelSignService 初始化, secret={}, token={}",
                props.getSecret(), props.getToken());
        return new ChannelSignService(props.getSecret(), props.getToken());
    }

    /**
     * 前缀模式：监听所有以 "template." 开头的 key，注入整个 Properties Bean。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys(prefix = "template")
    public TemplateService templateService(TemplateProperties props) {
        log.info("[V3] TemplateService 初始化, maxRetry={}, timeout={}",
                props.getMaxRetry(), props.getTimeout());
        return new TemplateService(props.getMaxRetry(), props.getTimeout());
    }

    /**
     * 精确模式 + 显式 dataId/group。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys(
            value = {"custom.feature.enabled"},
            dataId = "conditional-refresh-test-sample-v3",
            group = "DEFAULT_GROUP"
    )
    public FeatureToggleService featureToggleService(FeatureProperties props) {
        log.info("[V3] FeatureToggleService 初始化, enabled={}", props.getEnabled());
        return new FeatureToggleService(props.getEnabled());
    }

    /**
     * 暴露 {@link ConfigService} 供集成测试发布配置。
     */
    @Bean
    public ConfigService configService(NacosConfigManager nacosConfigManager) {
        return nacosConfigManager.getConfigService();
    }

    // ─── @ConfigurationProperties 绑定类 ─────────────────────────────

    @ConfigurationProperties(prefix = "channel.sign")
    public static class ChannelSignProperties {
        private String secret = "";
        private String token = "";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    @ConfigurationProperties(prefix = "template")
    public static class TemplateProperties {
        private int maxRetry = 3;
        private long timeout = 5000L;

        public int getMaxRetry() { return maxRetry; }
        public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }
    }

    @ConfigurationProperties(prefix = "custom.feature")
    public static class FeatureProperties {
        private boolean enabled = false;

        public boolean getEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    // ─── 内部服务类 ──────────────────────────────────────────────────

    public static class ChannelSignService {
        private final String secret;
        private final String token;

        public ChannelSignService(String secret, String token) {
            this.secret = secret;
            this.token = token;
        }

        public String getSecret() { return secret; }
        public String getToken() { return token; }

        public void destroy() {
            log.info("[V3] ChannelSignService.destroy() 被调用");
        }
    }

    public static class TemplateService {
        private final int maxRetry;
        private final long timeout;

        public TemplateService(int maxRetry, long timeout) {
            this.maxRetry = maxRetry;
            this.timeout = timeout;
        }

        public int getMaxRetry() { return maxRetry; }
        public long getTimeout() { return timeout; }

        public void destroy() {
            log.info("[V3] TemplateService.destroy() 被调用");
        }
    }

    public static class FeatureToggleService {
        private final boolean enabled;

        public FeatureToggleService(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() { return enabled; }

        public void destroy() {
            log.info("[V3] FeatureToggleService.destroy() 被调用");
        }
    }
}
