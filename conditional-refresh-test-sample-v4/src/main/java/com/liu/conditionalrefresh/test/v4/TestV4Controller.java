package com.liu.conditionalrefresh.test.v4;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestV4Controller {

    private final TestV4Beans.ChannelSignService channelSignService;
    private final TestV4Beans.TemplateService templateService;
    private final TestV4Beans.FeatureToggleService featureToggleService;

    public TestV4Controller(TestV4Beans.ChannelSignService channelSignService,
                            TestV4Beans.TemplateService templateService,
                            TestV4Beans.FeatureToggleService featureToggleService) {
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
        result.put("version", "4.0.x");
        return result;
    }
}
