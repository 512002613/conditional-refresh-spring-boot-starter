package com.liu.conditionalrefresh.test;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.liu.conditionalrefresh.annotation.RefreshOnKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 条件刷新测试验证模块的示例 Bean 配置。
 *
 * <p>定义了三个使用 {@code @RefreshOnKeys} 的 Bean，覆盖三种典型场景：
 * <ol>
 *   <li>ChannelSignService — 监听默认 (dataId, group) 下的 keys</li>
 *   <li>TemplateService — 监听默认 (dataId, group) 下的其他 keys</li>
 *   <li>FeatureToggleService — 显式指定 dataId 和 group</li>
 * </ol>
 */
@Configuration
public class TestBeans {

    private static final Logger log = LoggerFactory.getLogger(TestBeans.class);

    // ─── @RefreshOnKeys Bean ────────────────────────────────────────

    /**
     * 监听默认 dataId（spring.application.name）和 group 下的 channel.sign.* keys。
     * 当 channel.sign.secret 或 channel.sign.token 值变更时，此 Bean 会被重建。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys({"channel.sign.secret", "channel.sign.token"})
    public ChannelSignService channelSignService(Environment env) {
        String secret = env.getProperty("channel.sign.secret", "");
        String token = env.getProperty("channel.sign.token", "");
        log.info("ChannelSignService 初始化, secret={}, token={}", secret, token);
        return new ChannelSignService(secret, token);
    }

    /**
     * 监听默认 dataId 和 group 下的 template.* keys。
     * 当 template.max.retry 或 template.timeout 值变更时，此 Bean 会被重建。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys({"template.max.retry", "template.timeout"})
    public TemplateService templateService(Environment env) {
        int maxRetry = Integer.parseInt(env.getProperty("template.max.retry", "3"));
        long timeout = Long.parseLong(env.getProperty("template.timeout", "5000"));
        log.info("TemplateService 初始化, maxRetry={}, timeout={}", maxRetry, timeout);
        return new TemplateService(maxRetry, timeout);
    }

    /**
     * 暴露 ConfigService 供测试使用，通过 NacosConfigManager 获取。
     */
    @Bean
    public ConfigService configService(NacosConfigManager nacosConfigManager) {
        return nacosConfigManager.getConfigService();
    }

    /**
     * 显式指定 dataId 和 group，监听 custom.feature.enabled key。
     * 演示如何监听非默认 (dataId, group) 下的配置。
     */
    @Bean(destroyMethod = "destroy")
    @RefreshOnKeys(
            value = {"custom.feature.enabled"},
            dataId = "conditional-refresh-test-sample",
            group = "DEFAULT_GROUP"
    )
    public FeatureToggleService featureToggleService(Environment env) {
        boolean enabled = Boolean.parseBoolean(
                env.getProperty("custom.feature.enabled", "false"));
        log.info("FeatureToggleService 初始化, enabled={}", enabled);
        return new FeatureToggleService(enabled);
    }

    // ─── 内部服务类 ──────────────────────────────────────────────────

    /**
     * 渠道签名服务（示例 Bean）：持有 channel.sign.secret 和 channel.sign.token。
     */
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
            log.info("ChannelSignService.destroy() 被调用");
        }
    }

    /**
     * 模板服务（示例 Bean）：持有 template.max.retry 和 template.timeout。
     */
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
            log.info("TemplateService.destroy() 被调用");
        }
    }

    /**
     * 特性开关服务（示例 Bean）：持有 custom.feature.enabled。
     */
    public static class FeatureToggleService {
        private final boolean enabled;

        public FeatureToggleService(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() { return enabled; }

        public void destroy() {
            log.info("FeatureToggleService.destroy() 被调用");
        }
    }
}
