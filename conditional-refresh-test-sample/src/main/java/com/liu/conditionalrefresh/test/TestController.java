package com.liu.conditionalrefresh.test;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 条件刷新测试验证模块的 REST 控制器。
 *
 * <p>提供端点暴露 {@code @RefreshOnKeys} Bean 的当前状态，
 * 用于手动验证配置刷新是否生效。
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private final TestBeans.ChannelSignService channelSignService;
    private final TestBeans.TemplateService templateService;
    private final TestBeans.FeatureToggleService featureToggleService;

    public TestController(TestBeans.ChannelSignService channelSignService,
                          TestBeans.TemplateService templateService,
                          TestBeans.FeatureToggleService featureToggleService) {
        this.channelSignService = channelSignService;
        this.templateService = templateService;
        this.featureToggleService = featureToggleService;
    }

    /**
     * 返回 ChannelSignService 当前值。
     * 在 Nacos 修改 channel.sign.secret 后，等待 debounce 窗口再次调用，
     * 如果返回新值则说明条件刷新生效。
     */
    @GetMapping("/channel-sign")
    public Map<String, String> channelSign() {
        Map<String, String> result = new HashMap<>();
        result.put("secret", channelSignService.getSecret());
        result.put("token", channelSignService.getToken());
        return result;
    }

    /**
     * 返回 TemplateService 当前值。
     */
    @GetMapping("/template")
    public Map<String, Object> template() {
        Map<String, Object> result = new HashMap<>();
        result.put("maxRetry", templateService.getMaxRetry());
        result.put("timeout", templateService.getTimeout());
        return result;
    }

    /**
     * 返回 FeatureToggleService 当前值。
     */
    @GetMapping("/feature")
    public Map<String, Boolean> feature() {
        Map<String, Boolean> result = new HashMap<>();
        result.put("enabled", featureToggleService.isEnabled());
        return result;
    }

    /**
     * 健康检查端点。
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "UP");
        return result;
    }
}
