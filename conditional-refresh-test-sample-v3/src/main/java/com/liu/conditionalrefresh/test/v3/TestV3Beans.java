package com.liu.conditionalrefresh.test.v3;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.liu.conditionalrefresh.annotation.RefreshOnKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Spring Boot 3.5.x 测试验证模块的示例 Bean 配置。
 */
@Configuration
public class TestV3Beans {

    private static final Logger log = LoggerFactory.getLogger(TestV3Beans.class);

    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys({"channel.sign.secret", "channel.sign.token"})
    public ChannelSignService channelSignService(Environment env) {
        String secret = env.getProperty("channel.sign.secret", "");
        String token = env.getProperty("channel.sign.token", "");
        log.info("[V3] ChannelSignService 初始化, secret={}, token={}", secret, token);
        return new ChannelSignService(secret, token);
    }

    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys({"template.max.retry", "template.timeout"})
    public TemplateService templateService(Environment env) {
        int maxRetry = Integer.parseInt(env.getProperty("template.max.retry", "3"));
        long timeout = Long.parseLong(env.getProperty("template.timeout", "5000"));
        log.info("[V3] TemplateService 初始化, maxRetry={}, timeout={}", maxRetry, timeout);
        return new TemplateService(maxRetry, timeout);
    }

    @Bean
    public ConfigService configService(NacosConfigManager nacosConfigManager) {
        return nacosConfigManager.getConfigService();
    }

    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys(
            value = {"custom.feature.enabled"},
            dataId = "conditional-refresh-test-sample-v3",
            group = "DEFAULT_GROUP"
    )
    public FeatureToggleService featureToggleService(Environment env) {
        boolean enabled = Boolean.parseBoolean(
                env.getProperty("custom.feature.enabled", "false"));
        log.info("[V3] FeatureToggleService 初始化, enabled={}", enabled);
        return new FeatureToggleService(enabled);
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
