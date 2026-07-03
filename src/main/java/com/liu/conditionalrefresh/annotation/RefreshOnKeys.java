package com.liu.conditionalrefresh.annotation;

import java.lang.annotation.*;

/**
 * 声明一个 Bean 仅对指定的配置 Key 敏感。
 *
 * <p>当且仅当这些 Key 在 Nacos 配置中发生 <strong>值变更</strong> 时，
 * 该 Bean 才会被销毁并重建。未列出的 Key 变更不会影响该 Bean，
 * 从而实现"配置变更影响最小化"。
 *
 * <h3>适用范围</h3>
 * <ul>
 *   <li><strong>类级别</strong>：作用于 {@code @Component}、{@code @Service} 等类。</li>
 *   <li><strong>方法级别</strong>：作用于 {@code @Configuration} 类中的 {@code @Bean} 方法。</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Configuration
 * public class CosConfig {
 *
 *     @Bean(destroyMethod = "shutdown")
 *     @RefreshOnKeys({"cos.tencent.secretId", "cos.tencent.secretKey"})
 *     public COSClient cosClient(Environment env) {
 *         String id  = env.getProperty("cos.tencent.secretId");
 *         String key = env.getProperty("cos.tencent.secretKey");
 *         return new COSClient(id, key);
 *     }
 * }
 * }</pre>
 *
 * <p>以上 Bean 仅当 {@code cos.tencent.secretId} 或 {@code cos.tencent.secretKey}
 * 的 <em>值</em> 发生变化时才会重建，其他配置变更（如 {@code sms.sign.name}）不会触发。
 *
 * <h3>与 {@code @RefreshScope} 的关系</h3>
 * <p>标记 {@code @RefreshOnKeys} 的 Bean <strong>不得同时</strong>使用 {@code @RefreshScope}。
 * 启动时若检测到混用，将直接抛出异常，避免不可预期的刷新行为。
 *
 * <h3>占位符支持</h3>
 * <p>{@link #value()}、{@link #dataId()}、{@link #group()} 均支持 {@code ${...}} 占位符，
 * 在应用启动、环境就绪后统一解析。
 *
 * <p><strong>注意</strong>：该注解 <em>不包含</em> {@code @Scope} 语义，作用域设置由
 * {@link com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor}
 * 在 Bean 定义阶段自动完成。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see com.liu.conditionalrefresh.processor.RefreshOnKeysPostProcessor
 * @see com.liu.conditionalrefresh.scope.ConditionalRefreshScope
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RefreshOnKeys {

    /**
     * 需要监听的配置键列表（完整的 property key，如 {@code "cos.tencent.secretId"}）。
     *
     * <p>支持 {@code ${...}} 占位符，在环境就绪后统一解析。
     *
     * @return 配置的键名数组（不能为空）
     */
    String[] value();

    /**
     * 对应的 Nacos Data ID。
     *
     * <p>默认值为空字符串，将在环境就绪后解析为：
     * <ol>
     *     <li>{@code spring.cloud.nacos.config.prefix}（如果配置）</li>
     *     <li>{@code spring.application.name}（兜底）</li>
     * </ol>
     *
     * <p>支持 {@code ${...}} 占位符。
     *
     * @return Nacos Data ID
     */
    String dataId() default "";

    /**
     * 对应的 Nacos Group。
     *
     * <p>默认值为空字符串，将在环境就绪后解析为：
     * <ol>
     *     <li>{@code spring.cloud.nacs.config.group}（如果配置）</li>
     *     <li>{@code DEFAULT_GROUP}（兜底）</li>
     * </ol>
     *
     * <p>支持 {@code ${...}} 占位符。
     *
     * @return Nacos Group
     */
    String group() default "";
}
