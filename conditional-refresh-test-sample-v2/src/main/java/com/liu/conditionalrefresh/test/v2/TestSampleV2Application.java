package com.liu.conditionalrefresh.test.v2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 条件刷新框架 Spring Boot 2.7.x + Spring Cloud 2022.0.x 测试验证模块的应用入口。
 */
@SpringBootApplication(scanBasePackages = "com.liu.conditionalrefresh.test.v2")
public class TestSampleV2Application {

    public static void main(String[] args) {
        SpringApplication.run(TestSampleV2Application.class, args);
    }
}
