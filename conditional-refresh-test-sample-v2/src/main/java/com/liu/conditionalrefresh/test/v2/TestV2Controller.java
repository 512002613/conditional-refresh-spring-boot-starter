package com.liu.conditionalrefresh.test.v2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 条件刷新框架 V2 测试验证模块的 REST 端点。
 *
 * <p>提供 HTTP 接口手动验证刷新行为，无需依赖 Nacos 控制台。
 *
 * <p>本模块使用 SB 3.0.x + SC 2022.0.x（轻量 RefreshEvent 模式），
 * 验证条件刷新在新一代 Spring Cloud 刷新机制下是否正常工作 —— 配置变更时
 * 只刷新关联 Bean，不触发全量 context restart。
 */
@RestController
public class TestV2Controller {

    private static final Logger log = LoggerFactory.getLogger(TestV2Controller.class);
    @Autowired(required = false)
    private TestV2Beans.ChannelSignService channelSignService;

    @Autowired(required = false)
    private TestV2Beans.TemplateService templateService;

    @Autowired(required = false)
    private TestV2Beans.FeatureToggleService featureToggleService;

    /**
     * 返回所有 @RefreshOnKeys Bean 的当前状态。
     */
    @GetMapping("/v2/status")
    public Map<String, Object> status() {
        log.info("/v2/status called");
        Map<String, Object> result = new HashMap<>();
        if (channelSignService != null) {
            Map<String, Object> inner = new HashMap<>();
            inner.put("secret", channelSignService.getSecret());
            inner.put("token", channelSignService.getToken());
            result.put("channelSignService", inner);
        }
        if (templateService != null) {
            Map<String, Object> inner = new HashMap<>();
            inner.put("maxRetry", templateService.getMaxRetry());
            inner.put("timeout", templateService.getTimeout());
            result.put("templateService", inner);
        }
        if (featureToggleService != null) {
            Map<String, Object> inner = new HashMap<>();
            inner.put("enabled", featureToggleService.isEnabled());
            result.put("featureToggleService", inner);
        }
        return result;
    }

    /**
     * 健康检查端点。
     */
    @GetMapping("/v2/health")
    public String health() {
        return "V2 OK (SB3.0 + SC2022.0.x + SCA2022.0.0.0)";
    }
}
