package com.liu.conditionalrefresh.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigAutoConfiguration;
import com.liu.conditionalrefresh.listener.ConditionalRefreshListener;
import com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor;
import com.liu.conditionalrefresh.scope.ConditionalRefreshScope;
import com.liu.conditionalrefresh.scope.ConditionalScopeRegistrar;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 条件刷新的自动配置类。
 *
 * <p>在满足以下条件时启用：
 * <ul>
 *     <li>{@code spring-cloud-starter-alibaba-nacos-config} 在 classpath 中。</li>
 *     <li>{@code NacosConfigManager} Bean 可用（Nacos Config 自动配置已生效）。</li>
 *     <li>{@code conditional.refresh.enabled} 不为 {@code false}。</li>
 * </ul>
 *
 * <h2>注册顺序</h2>
 * <ol>
 *     <li>{@link ConditionalRefreshScope} —— 条件刷新作用域 Bean。</li>
 *     <li>{@link ConditionalScopeRegistrar} —— 作用域注册器（BeanFactoryPostProcessor）。</li>
 *     <li>{@link RefreshOnKeysPostProcessor} —— Bean 定义扫描改写（BDRPP）。</li>
 *     <li>{@link ConditionalRefreshProperties} —— 配置属性绑定。</li>
 *     <li>{@link ConditionalRefreshListener} —— Nacos 监听器（ApplicationReadyEvent 触发）。</li>
 * </ol>
 *
 * <h2>与全局 {@code @RefreshScope} 的关系</h2>
 * <p>本自动配置与 {@code NacosConfigAutoConfiguration} <strong>通过 @AutoConfigureAfter 解耦</strong>，
 * 确保在 Nacos 配置管理器注册之后才执行。两种刷新策略互不干扰。
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass(NacosConfigManager.class)
@ConditionalOnBean(NacosConfigManager.class)
@ConditionalOnProperty(prefix = "conditional.refresh", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(NacosConfigAutoConfiguration.class)
@EnableConfigurationProperties(ConditionalRefreshProperties.class)
public class ConditionalRefreshAutoConfiguration {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
            ConditionalRefreshAutoConfiguration.class);

    /**
     * 注册条件刷新作用域 Bean。
     *
     * <p>该 Bean 是 {@code "conditionalRefresh"} 作用域的实际持有者，
     * 会被 {@link ConditionalScopeRegistrar} 在 {@code postProcessBeanFactory}
     * 阶段注册到容器，也会被 {@link ConditionalRefreshListener} 注入使用。
     * 通过容器注入保证监听器与注册器引用同一个 scope 实例。
     *
     * @return 条件刷新作用域实例
     */
    @Bean
    @ConditionalOnMissingBean(ConditionalRefreshScope.class)
    public ConditionalRefreshScope conditionalRefreshScope() {
        log.debug("Registering ConditionalRefreshScope bean.");
        return new ConditionalRefreshScope();
    }

    /**
     * 注册作用域注册器（{@link org.springframework.beans.factory.config.BeanFactoryPostProcessor}）。
     *
     * <p>该 Bean 在 Spring 容器处理 Bean 工厂阶段自动执行，
     * 将容器中的同一个 {@link ConditionalRefreshScope} 注册为名为
     * {@code "conditionalRefresh"} 的作用域。
     *
     * @param scope 容器中已定义的 {@link ConditionalRefreshScope} Bean
     * @return 作用域注册器
     */
    @Bean
    @ConditionalOnMissingBean
    public ConditionalScopeRegistrar conditionalScopeRegistrar(
            ConditionalRefreshScope scope) {
        log.debug("Registering ConditionalScopeRegistrar BeanFactoryPostProcessor.");
        return new ConditionalScopeRegistrar(scope);
    }

    /**
     * 注册 Bean 定义扫描后处理器（{@link RefreshOnKeysPostProcessor}）。
     *
     * <p>该后处理器：
     * <ul>
     *     <li>扫描所有标记了 {@code @RefreshOnKeys} 的 Bean。</li>
     *     <li>设置 scope = "conditionalRefresh" + proxyMode = TARGET_CLASS。</li>
     *     <li>互斥校验：禁止与 {@code @RefreshScope} 混用。</li>
     *     <li>收集元数据供监听器使用。</li>
     * </ul>
     *
     * @return Bean 定义扫描后处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public RefreshOnKeysPostProcessor refreshOnKeysPostProcessor() {
        log.debug("Registering RefreshOnKeysPostProcessor BDRPP.");
        return new RefreshOnKeysPostProcessor();
    }

    /**
     * 注册条件刷新监听器。
     *
     * <p>监听 {@code EnvironmentChangeEvent}（PropertySource 更新后发布），
     * 通过反向索引精准刷新受影响的 Bean。
     *
     * <p>不再直连 Nacos SDK — 依赖 Spring Cloud 的事件机制保证 PropertySource-first 时序。
     *
     * @param scope        条件刷新作用域
     * @param postProcessor 元数据后处理器（用于获取 MetadataCollector）
     * @param environment  Spring Environment
     * @return 条件刷新监听器
     */
    @Bean
    @ConditionalOnMissingBean
    public ConditionalRefreshListener conditionalRefreshListener(
            ConditionalRefreshScope scope,
            RefreshOnKeysPostProcessor postProcessor,
            Environment environment) {
        log.info("Conditional refresh auto-configuration activated. " +
                "Beans annotated with @RefreshOnKeys will be refreshed on key changes.");
        return new ConditionalRefreshListener(scope, postProcessor, environment);
    }
}
