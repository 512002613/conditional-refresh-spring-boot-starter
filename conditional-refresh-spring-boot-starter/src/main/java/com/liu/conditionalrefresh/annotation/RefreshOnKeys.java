package com.liu.conditionalrefresh.annotation;

import java.lang.annotation.*;

/**
 * 声明一个 Bean 仅对指定的配置 Key 敏感。
 *
 * <p>当且仅当这些 Key 在 Nacos 配置中发生 <strong>值变更</strong> 时，
 * 该 Bean 才会被销毁并重建。未列出的 Key 变更不会影响该 Bean，
 * 从而实现"配置变更影响最小化"。
 *
 * <h2>两种监听模式（互斥，必选其一）</h2>
 * <ul>
 *   <li><strong>精确模式</strong>（{@link #value()}）：显式列出每个监听的 key，
 *       仅当列出的 key 值变更时触发刷新。</li>
 *   <li><strong>前缀模式</strong>（{@link #prefix()}）：监听该前缀下的所有 key，
 *       任何以 {@code prefix.} 开头的 key 变更都会触发刷新。
 *       适用于工厂方法注入整个 {@code @ConfigurationProperties} Bean 的场景。</li>
 * </ul>
 *
 * <h2>适用范围</h2>
 * <ul>
 *   <li><strong>类级别</strong>：作用于 {@code @Component}、{@code @Service} 等类。</li>
 *   <li><strong>方法级别</strong>：作用于 {@code @Configuration} 类中的 {@code @Bean} 方法。</li>
 * </ul>
 *
 * <h2>使用示例 — 精确模式</h2>
 * <pre>
 * &#64;Configuration
 * public class CosConfig {
 *
 *     &#64;Bean(destroyMethod = "shutdown")
 *     &#64;RefreshOnKeys({"cos.tencent.secretId", "cos.tencent.secretKey"})
 *     public COSClient cosClient(&#64;Value("${cos.tencent.secretId}") String id,
 *                                &#64;Value("${cos.tencent.secretKey}") String key) {
 *         return new COSClient(id, key);
 *     }
 * }
 * </pre>
 *
 * <h2>使用示例 — 前缀模式（注入 @ConfigurationProperties）</h2>
 * <pre>
 * &#64;ConfigurationProperties(prefix = "channel.sign")
 * public class ChannelSignProperties {
 *     private String secret;
 *     private String token;
 *     // getters/setters
 * }
 *
 * &#64;Configuration
 * public class ChannelConfig {
 *     &#64;Bean(destroyMethod = "destroy")
 *     &#64;RefreshOnKeys(prefix = "channel.sign")
 *     public ChannelSignService channelSignService(ChannelSignProperties props) {
 *         return new ChannelSignService(props.getSecret(), props.getToken());
 *     }
 * }
 * </pre>
 *
 * <p>前缀模式下，任何 {@code channel.sign.*} 下的 key 变更都会触发该 Bean 刷新。
 *
 * <h2>与 {@code @RefreshScope} 的关系</h2>
 * <p>标记 {@code @RefreshOnKeys} 的 Bean <strong>不得同时</strong>使用 {@code @RefreshScope}。
 * 启动时若检测到混用，将直接抛出异常，避免不可预期的刷新行为。
 *
 * <h2>占位符支持</h2>
 * <p>{@link #value()}、{@link #prefix()}、{@link #dataId()}、{@link #group()}
 * 均支持 {@code ${...}} 占位符，在应用启动、环境就绪后统一解析。
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
     * <p>与 {@link #prefix()} <strong>互斥</strong>：两者不能同时非空，也不能同时为空。
     *
     * <p>支持 {@code ${...}} 占位符，在环境就绪后统一解析。
     *
     * @return 配置的键名数组
     */
    String[] value() default {};

    /**
     * 需要监听的配置前缀（如 {@code "channel.sign"}）。
     *
     * <p>与 {@link #value()} <strong>互斥</strong>：两者不能同时非空，也不能同时为空。
     *
     * <p>前缀模式下，任何以 {@code prefix + "."} 开头的 key 变更都会触发该 Bean 刷新。
     * 适用于工厂方法注入整个 {@code @ConfigurationProperties} Bean 的场景。
     *
     * <p>支持 {@code ${...}} 占位符，在环境就绪后统一解析。
     *
     * @return 配置前缀
     */
    String prefix() default "";

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
     *     <li>{@code spring.cloud.nacos.config.group}（如果配置）</li>
     *     <li>{@code DEFAULT_GROUP}（兜底）</li>
     * </ol>
     *
     * <p>支持 {@code ${...}} 占位符。
     *
     * @return Nacos Group
     */
    String group() default "";
}
