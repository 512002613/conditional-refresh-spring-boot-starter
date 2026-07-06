package com.liu.conditionalrefresh.test.v3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 条件刷新框架 Spring Boot 3.5.x 测试验证模块的应用入口。
 */
@SpringBootApplication(scanBasePackages = "com.liu.conditionalrefresh.test.v3")
public class TestSampleV3Application {

    public static void main(String[] args) {
        SpringApplication.run(TestSampleV3Application.class, args);
    }
}
