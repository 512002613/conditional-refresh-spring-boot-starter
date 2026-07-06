package com.liu.conditionalrefresh.test.v3;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot 3.5.x 测试验证模块的 REST 控制器。
 */
@RestController
@RequestMapping("/test")
public class TestV3Controller {

    private final TestV3Beans.ChannelSignService channelSignService;
    private final TestV3Beans.TemplateService templateService;
    private final TestV3Beans.FeatureToggleService featureToggleService;

    public TestV3Controller(TestV3Beans.ChannelSignService channelSignService,
                            TestV3Beans.TemplateService templateService,
                            TestV3Beans.FeatureToggleService featureToggleService) {
        this.channelSignService = channelSignService;
        this.templateService = templateService;
        this.featureToggleService = featureToggleService;
    }

    @GetMapping("/channel-sign")
    public Map<String, String> channelSign() {
        Map<String, String> result = new HashMap<>();
        result.put("secret", channelSignService.getSecret());
        result.put("token", channelSignService.getToken());
        return result;
    }

    @GetMapping("/template")
    public Map<String, Object> template() {
        Map<String, Object> result = new HashMap<>();
        result.put("maxRetry", templateService.getMaxRetry());
        result.put("timeout", templateService.getTimeout());
        return result;
    }

    @GetMapping("/feature")
    public Map<String, Boolean> feature() {
        Map<String, Boolean> result = new HashMap<>();
        result.put("enabled", featureToggleService.isEnabled());
        return result;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "UP");
        result.put("version", "3.5.x");
        return result;
    }
}
