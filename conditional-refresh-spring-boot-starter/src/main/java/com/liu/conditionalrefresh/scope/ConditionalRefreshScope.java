package com.liu.conditionalrefresh.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.cloud.context.scope.GenericScope;

/**
 * 条件刷新作用域，管理所有标记了 {@link com.liu.conditionalrefresh.annotation.RefreshOnKeys}
 * 的 Bean 的生命周期。
 *
 * <p>继承自 Spring Cloud 的 {@link GenericScope}，复用其 Bean 创建、缓存、
 * 销毁 {@code destroyMethod} 等基础设施。作用域名称为 {@code "conditionalRefresh"}。
 *
 * <h3>刷新行为</h3>
 * <p>{@link #refresh(String)} 会：
 * <ol>
 *     <li>从缓存中移除 Bean 实例（自动执行其 {@code destroyMethod}）。</li>
 *     <li>记录刷新日志。</li>
 *     <li>Bean 的实际重建延迟到下一次通过 scoped-proxy 访问时惰性触发。</li>
 *     <li>若后续重建失败，<code>GenericScope</code> 会缓存异常，
 *         所有后续对该 Bean 的方法调用都会重抛异常，直到配置修复。</li>
 * </ol>
 *
 * <h3>返回值语义（重要）</h3>
 * <p>{@link #refresh(String)} 仅<strong>销毁旧实例</strong>，
 * 返回 {@code true} 表示旧实例已成功销毁（或不存在），
 * <strong>并不代表新实例已创建成功</strong>。
 * 新实例在下一次通过 scoped-proxy 访问该 Bean 时由
 * {@link GenericScope#get(String, ObjectFactory)} 惰性创建。
 *
 * <p>调用方不应将 {@code true} 作为"刷新完全成功"的依据，
 * 真正的成功确认应通过后续 Bean 访问是否抛异常来判断。
 *
 * <h3>为什么不立即重建？</h3>
 * <ul>
 *     <li>避免在 Nacos 回调线程中执行重操作（如建立连接池）。</li>
 *     <li>对齐原生 <code>RefreshScope</code> 的行为（也是惰性重建）。</li>
 *     <li>去抖器已在外层保证不会频繁触发，无需在 scope 层做预校验。</li>
 * </ul>
 *
 * <h3>与原生 {@code RefreshScope} 的区别</h3>
 * <ul>
 *     <li>{@code RefreshScope}：全量刷新，监听所有环境变更事件。</li>
 *     <li>{@code ConditionalRefreshScope}：条件刷新，仅当监听的 Key 值发生
 *         实际变更时由
 *         {@link com.liu.conditionalrefresh.listener.ConditionalRefreshListener}
 *         主动触发。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link GenericScope} 的缓存本身线程安全。本方法需在调用端持有 Bean 级锁
 * （参见 {@code ConditionalRefreshListener} 中的锁机制）的前提下调用。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see GenericScope
 * @see com.liu.conditionalrefresh.listener.ConditionalRefreshListener
 */
public class ConditionalRefreshScope extends GenericScope {

    private static final Logger log = LoggerFactory.getLogger(ConditionalRefreshScope.class);

    /** 作用域名称，与注册时保持一致。 */
    public static final String SCOPE_NAME = "conditionalRefresh";

    /**
     * 构造条件刷新作用域，设置作用域名称为 {@value #SCOPE_NAME}。
     */
    public ConditionalRefreshScope() {
        super.setName(SCOPE_NAME);
    }

    /**
     * 刷新指定 Bean — 仅销毁旧实例，新实例将在下次访问时惰性创建。
     *
     * <p><strong>前置条件</strong>：调用方已持有该 Bean 的专属锁
     * （见 {@code ConditionalRefreshListener}），保证同一 Bean 同一时刻只有一个刷新操作。
     *
     * <p><strong>回滚策略</strong>：本方法 <em>不实施回滚</em>。
     * 刷新失败后直接抛出异常，让运维感知。原因：
     * <ul>
     *     <li>Bean 的 {@code destroyMethod}（如 {@code shutdown()}）已执行；</li>
     *     <li>强行将旧对象放回缓存意味着后续调用拿到已关闭的连接池，
     *         必然 NPE / Connection refused。</li>
     * </ul>
     *
     * <h3>惰性重建语义</h3>
     * <p>本方法仅销毁旧实例。新实例在下次通过 scoped-proxy 访问该 Bean 时
     * 由 {@link GenericScope#get(String, ObjectFactory)} 惰性地创建并缓存。
     * 这种方式的好处：
     * <ul>
     *     <li>不阻塞 Nacos 回调线程。</li>
     *     <li>对齐原生 {@code RefreshScope} 的语义。</li>
     *     <li>自然支持去抖（快速多次变更只触发一次最终访问）。</li>
     * </ul>
     *
     * @param beanName 要刷新的 Bean 名称（非空）
     * @return 若缓存中存在该 Bean 并已成功销毁返回 {@code true}；
     *         若缓存中本就没有该 Bean（无需销毁），返回 {@code false}
     * @throws IllegalArgumentException beanName 为空
     */
    public boolean refresh(String beanName) {
        if (beanName == null || beanName.isEmpty()) {
            throw new IllegalArgumentException("beanName must not be null or empty");
        }

        // 从缓存移除旧实例，并执行 destroyMethod（如有）。
        // 注意：此时仅销毁旧实例，新实例尚未创建。
        // 真正的重建将在下次通过 scoped-proxy 访问时触发 GenericScope.get()。
        Object oldInstance = removeBeanSafely(beanName);
        if (oldInstance == null) {
            log.debug("Bean '{}' not found in conditional refresh scope, skip refresh.", beanName);
            return false;
        }

        log.info("Bean '{}' destroyed in conditional refresh scope. " +
                "New instance will be created lazily on next proxy access.", beanName);
        return true;
    }

    /**
     * 安全地从缓存中移除 Bean。
     *
     * <p>若 Bean 的 {@code destroyMethod} 抛出异常，仅记录告警日志，
     * 不阻断后续流程（避免影响刷新）。
     *
     * @param beanName Bean 名称
     * @return 被移除的旧实例，若不存在或 destroy 抛异常则返回 {@code null}
     */
    private Object removeBeanSafely(String beanName) {
        try {
            return super.remove(beanName);
        } catch (Exception e) {
            // destroyMethod 异常不阻断，仅告警
            log.warn("Error while destroying bean '{}' in conditional refresh scope: {}",
                    beanName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取作用域名称。
     *
     * @return {@value #SCOPE_NAME}
     */
    @Override
    public String getName() {
        return SCOPE_NAME;
    }
}
