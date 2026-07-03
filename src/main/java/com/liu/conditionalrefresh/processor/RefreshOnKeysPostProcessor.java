package com.liu.conditionalrefresh.processor;

import com.liu.conditionalrefresh.annotation.RefreshOnKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;

import java.util.Map;

/**
 * Bean 定义注册后处理器：扫描 {@link RefreshOnKeys} 注解并自动设置作用域和代理模式。
 *
 * <h3>核心职责</h3>
 * <ol>
 *     <li><strong>扫描</strong>：遍历所有 Bean 定义，识别标记了 {@code @RefreshOnKeys} 的 Bean。</li>
 *     <li><strong>验证</strong>：确保同一 Bean 不同时标记 {@code @RefreshScope}（互斥）。</li>
 *     <li><strong>改写</strong>：将 Bean 的 scope 设为 {@code "conditionalRefresh"}，
 *         并强制开启 {@code ScopedProxyMode.TARGET_CLASS} 代理。</li>
 *     <li><strong>收集</strong>：将元数据注入 {@link MetadataCollector} 供监听器使用。</li>
 * </ol>
 *
 * <h3>实现要点</h3>
 * <ul>
 *     <li>实现 {@link BeanDefinitionRegistryPostProcessor}，在 Bean <strong>实例化前</strong>
 *         阶段操作元数据，<strong>绝不</strong>触发 {@code getBean} 调用。</li>
 *     <li>通过 {@link AnnotatedTypeMetadata} 反射读取注解，不触发实例化。</li>
 *     <li>实现 {@link Ordered} 返回高优先级，确保在其他 BPP 改写 scope 之前完成。</li>
 * </ul>
 *
 * <h3>互斥校验</h3>
 * <p>同一个 Bean <strong>不能同时</strong>标记 {@code @RefreshScope} 和 {@code @RefreshOnKeys}，
 * 否则两种刷新机制冲突、行为不可预期。本后处理器在检测到混用时直接抛出异常，
 * 使应用在<strong>启动阶段</strong>即失败，避免运行时隐患。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see RefreshOnKeys
 * @see MetadataCollector
 * @see com.liu.conditionalrefresh.scope.ConditionalRefreshScope
 */
public class RefreshOnKeysPostProcessor
        implements BeanDefinitionRegistryPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RefreshOnKeysPostProcessor.class);

    /** 条件刷新作用域名称。 */
    private static final String CONDITIONAL_REFRESH_SCOPE =
            com.liu.conditionalrefresh.scope.ConditionalRefreshScope.SCOPE_NAME;

    /** Spring Cloud 原生刷新作用域名称。 */
    private static final String REFRESH_SCOPE = "refresh";

    /** @RefreshScope 注解的完全限定类名（避免编译期依赖 spring-cloud-context）。 */
    private static final String REFRESH_SCOPE_ANNOTATION_CLASS =
            "org.springframework.cloud.context.config.annotation.RefreshScope";

    /** 元数据收集器。 */
    private final MetadataCollector collector = new MetadataCollector();

    /**
     * 处理 Bean 定义注册表，扫描并改写 {@code @RefreshOnKeys} 标记的 Bean。
     *
     * @param registry Bean 定义注册表
     */
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);
            processBeanDefinition(beanName, bd);
        }
    }

    /**
     * {@link BeanDefinitionRegistryPostProcessor#postProcessBeanFactory} 的空实现。
     * 所有逻辑在 {@link #postProcessBeanDefinitionRegistry} 中完成。
     *
     * @param beanFactory 可配置的 Bean 工厂
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 空实现：不在此阶段操作，所有改写在 postProcessBeanDefinitionRegistry 完成。
    }

    /**
     * 返回处理器的执行顺序。
     *
     * <p>返回 {@link Ordered#HIGHEST_PRECEDENCE} + 10，确保在 Spring Cloud 的
     * {@code ConfigurationClassPostProcessor} 之后执行（它已完成 Bean 定义的扫描），
     * 但在其他可能依赖 scope 属性的后处理器之前执行。
     *
     * @return 排序值
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    /**
     * 获取内部元数据收集器。
     *
     * <p>由 {@link com.liu.conditionalrefresh.listener.ConditionalRefreshListener}
     * 使用，读取收集到的原始元数据。
     *
     * @return 元数据收集器
     */
    public MetadataCollector getCollector() {
        return collector;
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────

    /**
     * 处理单个 Bean 定义，若标记了 {@code @RefreshOnKeys} 则进行改写和收集。
     *
     * @param beanName Bean 名称
     * @param bd       对应的 Bean 定义
     */
    private void processBeanDefinition(String beanName, BeanDefinition bd) {
        // 尝试提取 @RefreshOnKeys 注解
        RefreshOnKeysAnnotationMetadata metadata = findAnnotation(bd);
        if (metadata == null) {
            return;
        }

        // 互斥校验：不能同时标记 @RefreshScope
        checkRefreshScopeConflict(beanName, bd, metadata);

        // 改写 Bean 定义：设置条件刷新作用域和强制代理
        rewriteBeanDefinition(beanName, bd);

        // 收集元数据到 MetadataCollector（不触发实例化）
        collector.add(beanName, metadata.dataId(), metadata.group(), metadata.keys());

        log.debug("Bean '{}' registered for conditional refresh, keys={}, dataId={}, group={}",
                beanName, metadata.keys(), metadata.dataId(), metadata.group());
    }

    /**
     * 查找 Bean 定义上的 {@code @RefreshOnKeys} 注解。
     *
     * <p>分两种来源：
     * <ul>
     *     <li>{@code @Bean} 方法：注解在工厂方法上（method-level）。</li>
     *     <li>{@code @Component} 类：注解在类上（class-level）。</li>
     * </ul>
     *
     * @param bd 要检查的 Bean 定义
     * @return 注解元数据对象，若不存在则返回 {@code null}
     */
    private RefreshOnKeysAnnotationMetadata findAnnotation(BeanDefinition bd) {
        if (!(bd instanceof AnnotatedBeanDefinition)) {
            return null;
        }
        AnnotatedBeanDefinition abd = (AnnotatedBeanDefinition) bd;

        // 场景 1：@Bean 方法（有工厂方法元数据）
        // 注意：@Bean 方法上的注解必须通过工厂方法元数据来读取，
        // 类级元数据只包含 @Configuration 上的注解。
        if (abd.getFactoryMethodName() != null) {
            org.springframework.core.type.MethodMetadata factoryMeta = abd.getFactoryMethodMetadata();
            if (factoryMeta != null) {
                MergedAnnotations annotations = factoryMeta.getAnnotations();
                if (annotations.isPresent(RefreshOnKeys.class)) {
                    return extractMetadataFromMergedAnnotations(annotations);
                }
            }
        }

        // 场景 2：@Component 类（无工厂方法）
        AnnotatedTypeMetadata metadata = abd.getMetadata();
        if (!(metadata instanceof MethodMetadata)) {
            MergedAnnotations annotations = metadata.getAnnotations();
            if (annotations.isPresent(RefreshOnKeys.class)) {
                return extractMetadataFromMergedAnnotations(annotations);
            }
        }

        return null;
    }

    /**
     * 从 {@link MergedAnnotations} 提取 {@code @RefreshOnKeys} 的完整元数据。
     *
     * @param annotations 已合成的注解集合
     * @return 提取到的注解元数据
     */
    private RefreshOnKeysAnnotationMetadata extractMetadataFromMergedAnnotations(
            MergedAnnotations annotations) {
        // 通过 MergedAnnotation API 提取注解属性值
        MergedAnnotation<RefreshOnKeys> merged = annotations.get(RefreshOnKeys.class);

        String[] keys = merged.getStringArray("value");
        String dataId = merged.getString("dataId");
        String group = merged.getString("group");

        return new RefreshOnKeysAnnotationMetadata(keys, dataId, group);
    }

    /**
     * 检查是否与 {@code @RefreshScope} 冲突。
     *
     * <p>直接检查 Bean 定义的元数据上是否同时存在两个注解（不依赖运行时 scope 值），
     * 这是最可靠的冲突检测方法。
     *
     * @param beanName Bean 名称
     * @param bd       对应的 Bean 定义
     * @param metadata 已被提取的 {@code @RefreshOnKeys} 元数据（用于错误信息）
     * @throws BeanDefinitionStoreException 若同时存在两个注解
     */
    private void checkRefreshScopeConflict(String beanName, BeanDefinition bd,
                                          RefreshOnKeysAnnotationMetadata metadata) {
        if (bd instanceof AnnotatedBeanDefinition) {
            AnnotatedBeanDefinition abd = (AnnotatedBeanDefinition) bd;
            // 通过元数据直接判断 @RefreshScope 是否存在（不依赖 scope 运行时值）
            boolean hasRefreshScope = abd.getMetadata()
                    .isAnnotated(REFRESH_SCOPE_ANNOTATION_CLASS);
            if (hasRefreshScope) {
                throw new BeanDefinitionStoreException(
                        "Bean '" + beanName + "' cannot have both @RefreshScope and " +
                        "@RefreshOnKeys. Please use only one refresh strategy.");
            }
        }
    }

    /**
     * 改写 Bean 定义，设置条件刷新作用域和代理模式。
     *
     * <p><strong>关键</strong>：必须通过 {@link AbstractBeanDefinition#setProxyMode}
     * 设置代理，而非使用 {@code BeanDefinition.setAttribute(...)} 魔法字符串，
     * 因为只有 {@code setProxyMode} 才会触发 Spring 的 scoped-proxy 创建机制。
     *
     * @param beanName Bean 名称
     * @param bd       对应的 Bean 定义
     */
    private void rewriteBeanDefinition(String beanName, BeanDefinition bd) {
        if (bd instanceof AbstractBeanDefinition) {
            AbstractBeanDefinition abd = (AbstractBeanDefinition) bd;
            abd.setScope(CONDITIONAL_REFRESH_SCOPE);

            // 关键修复：通过反射设置 proxyMode 字段，让 ScopedProxyCreator 创建 scoped-proxy。
            // Spring 5.x 的 AbstractBeanDefinition 不直接暴露 setProxyMode public 方法，
            // 但通过反射设置 proxyMode 字段可以正确驱动 ScopedProxyUtils。
            // 注意：proxyMode 字段是 int 类型，需传 ScopedProxyMode.ordinal() 值。
            try {
                java.lang.reflect.Field proxyModeField =
                        AbstractBeanDefinition.class.getDeclaredField("proxyMode");
                proxyModeField.setAccessible(true);
                proxyModeField.setInt(abd, ScopedProxyMode.TARGET_CLASS.ordinal());
                log.debug("Bean '{}' scope='{}', proxyMode=TARGET_CLASS (reflection).",
                        beanName, CONDITIONAL_REFRESH_SCOPE);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // 回退：若 Spring 版本无此字段，使用 setAttribute（仍能驱动 ScopedProxyCreator）
                abd.setAttribute(
                        "org.springframework.aop.scope.ScopedProxyUtils.proxyMode",
                        ScopedProxyMode.TARGET_CLASS);
                log.debug("Bean '{}' scope='{}', proxyMode set via attribute fallback.",
                        beanName, CONDITIONAL_REFRESH_SCOPE);
            }
        } else {
            // 理论上不会进入（Spring Boot 默认返回 AbstractBeanDefinition）
            log.warn("Bean '{}' is not an AbstractBeanDefinition, cannot rewrite. " +
                    "This may indicate an unsupported Bean registration approach.",
                    beanName);
        }
    }

    // ─── 内部数据结构 ─────────────────────────────────────────────────

    /**
     * {@code @RefreshOnKeys} 注解的元数据载体（避免在 Collection 层引入编译期依赖）。
     *
     * <p>使用 final 类是因为项目基于 JDK 11（不兼容 record），
     * 但保持不可变语义。
     *
     * @param keys   配置键名数组
     * @param dataId Nacos Data ID（可能为空）
     * @param group  Nacos Group（可能为空）
     */
    private static final class RefreshOnKeysAnnotationMetadata {

        private final String[] keys;

        private final String dataId;

        private final String group;

        RefreshOnKeysAnnotationMetadata(String[] keys, String dataId, String group) {
            this.keys = keys;
            this.dataId = dataId;
            this.group = group;
        }

        /**
         * 获取配置键名数组。
         *
         * @return 键名数组（非空）
         */
        String[] keys() {
            return keys;
        }

        /**
         * 获取 Nacos Data ID（可能为空）。
         *
         * @return Data ID 字符串
         */
        String dataId() {
            return dataId;
        }

        /**
         * 获取 Nacos Group（可能为空）。
         *
         * @return Group 字符串
         */
        String group() {
            return group;
        }
    }
}
