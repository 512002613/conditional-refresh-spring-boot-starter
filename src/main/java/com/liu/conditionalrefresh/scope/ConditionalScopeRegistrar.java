package com.liu.conditionalrefresh.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 条件刷新作用域注册器。
 *
 * <p>在 Spring 容器初始化阶段（{@link BeanFactoryPostProcessor}）将容器中的
 * {@link ConditionalRefreshScope} Bean 注册为名为 {@value ConditionalRefreshScope#SCOPE_NAME}
 * 的自定义作用域。
 *
 * <p><strong>关键设计决策</strong>：作用域注册器本身是一个 {@link BeanFactoryPostProcessor}，
 * 自动注入容器中已定义的 {@link ConditionalRefreshScope} Bean（由
 * {@link com.liu.conditionalrefresh.config.ConditionalRefreshAutoConfiguration} 提供），
 * 确保 {@code "conditionalRefresh"} 作用域引用的实例与监听器中注入的实例是同一个，
 * 避免刷新操作找不到 Bean 缓存。
 *
 * <h3>作用域语义</h3>
 * <p>与原生 {@code "refresh"} 作用域互相独立：
 * <ul>
 *     <li>{@code "refresh"}：Spring Cloud 的 {@code RefreshScope}，
 *         监听 {@code ContextRefreshedEvent} / {@code EnvironmentChangeEvent}，
 *         全量刷新所有标记 Bean。</li>
 *     <li>{@code "conditionalRefresh"}：本框架的作用域，
 *         仅由 {@link com.liu.conditionalrefresh.listener.ConditionalRefreshListener}
 *         按条件触发精确刷新。</li>
 * </ul>
 *
 * <h3>注册时机</h3>
 * <p>通过 {@link BeanFactoryPostProcessor#postProcessBeanFactory} 注册，
 * 确保在 Bean <strong>实例化之前</strong>作用域已被容器识别。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see ConditionalRefreshScope
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 */
public class ConditionalScopeRegistrar implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConditionalScopeRegistrar.class);

    /** 由 {@code com.liu.conditionalrefresh.config.ConditionalRefreshAutoConfiguration} 注入。 */
    private final ConditionalRefreshScope scope;

    /**
     * 构造作用域注册器。
     *
     * @param scope 容器中已定义的 {@link ConditionalRefreshScope} Bean
     */
    public ConditionalScopeRegistrar(ConditionalRefreshScope scope) {
        this.scope = scope;
    }

    /**
     * 在所有 Bean 定义加载完成后、实例化之前，注册条件刷新作用域。
     *
     * @param beanFactory Spring 的 ConfigurableListableBeanFactory
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 注册容器中的同一个 scope 实例，避免实例不一致
        beanFactory.registerScope(ConditionalRefreshScope.SCOPE_NAME, scope);
        log.info("Custom scope '{}' registered with ConditionalRefreshScope instance.",
                ConditionalRefreshScope.SCOPE_NAME);
    }
}
