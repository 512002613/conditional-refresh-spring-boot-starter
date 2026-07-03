package com.liu.conditionalrefresh.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 条件刷新框架测试验证模块的应用入口。
 *
 * <p>启动方式：
 * <pre>{@code
 *   java -jar conditional-refresh-test-sample-1.0.0.jar
 * }</pre>
 *
 * <p>验证端点：
 * <ul>
 *   <li>{@code GET /test/health} — 健康检查</li>
 *   <li>{@code GET /test/channel-sign} — ChannelSignService 当前值</li>
 *   <li>{@code GET /test/template} — TemplateService 当前值</li>
 *   <li>{@code GET /test/feature} — FeatureToggleService 当前值</li>
 * </ul>
 *
 * <p>验证步骤：
 * <ol>
 *   <li>启动应用，观察日志确认 Nacos 连接 + Listener 注册成功</li>
 *   <li>调用 {@code /test/channel-sign} 查看初始值</li>
 *   <li>在 Nacos 控制台修改 {@code channel.sign.secret}</li>
 *   <li>等待 debounce 窗口（默认 500ms）后再次调用端点确认值已更新</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.liu.conditionalrefresh.test")
public class TestSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestSampleApplication.class, args);
    }
}
