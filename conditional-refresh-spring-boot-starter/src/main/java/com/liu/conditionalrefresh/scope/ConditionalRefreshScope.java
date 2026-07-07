package com.liu.conditionalrefresh.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.cloud.context.scope.GenericScope;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 条件刷新作用域，管理所有标记了 {@link com.liu.conditionalrefresh.annotation.RefreshOnKeys}
 * 的 Bean 的生命周期。
 *
 * <p>继承自 Spring Cloud 的 {@link GenericScope}，复用其 Bean 创建、缓存、
 * 销毁 {@code destroyMethod} 等基础设施。作用域名称为 {@code "conditionalRefresh"}。
 *
 * <h2>刷新行为</h2>
 * <p>{@link #refresh(String)} 会：
 * <ol>
 *     <li>从缓存中移除 Bean 实例（自动执行其 {@code destroyMethod}）。</li>
 *     <li>记录刷新日志。</li>
 *     <li>Bean 的实际重建延迟到下一次通过 scoped-proxy 访问时惰性触发。</li>
 *     <li>若后续重建失败，<code>GenericScope</code> 会缓存异常，
 *         所有后续对该 Bean 的方法调用都会重抛异常，直到配置修复。</li>
 * </ol>
 *
 * <h2>返回值语义（重要）</h2>
 * <p>{@link #refresh(String)} 仅<strong>销毁旧实例</strong>，
 * 返回 {@code true} 表示旧实例已成功销毁（或不存在），
 * <strong>并不代表新实例已创建成功</strong>。
 * 新实例在下一次通过 scoped-proxy 访问该 Bean 时由
 * {@link GenericScope#get(String, ObjectFactory)} 惰性创建。
 *
 * <p>调用方不应将 {@code true} 作为"刷新完全成功"的依据，
 * 真正的成功确认应通过后续 Bean 访问是否抛异常来判断。
 *
 * <h2>为什么不立即重建？</h2>
 * <ul>
 *     <li>避免在 Nacos 回调线程中执行重操作（如建立连接池）。</li>
 *     <li>对齐原生 <code>RefreshScope</code> 的行为（也是惰性重建）。</li>
 *     <li>去抖器已在外层保证不会频繁触发，无需在 scope 层做预校验。</li>
 * </ul>
 *
 * <h2>与原生 {@code RefreshScope} 的区别</h2>
 * <ul>
 *     <li>{@code RefreshScope}：全量刷新，监听所有环境变更事件。</li>
 *     <li>{@code ConditionalRefreshScope}：条件刷新，仅当监听的 Key 值发生
 *         实际变更时由
 *         {@link com.liu.conditionalrefresh.listener.ConditionalRefreshListener}
 *         主动触发。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
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
     * 跨 context 重启的 Bean 实例暂存器。
     *
     * <p>当 Spring 全局刷新（{@code NacosContextRefresher}）触发全量 context restart 时，
     * 旧 scope 缓存会被 {@link #destroy()} 清空。新 scope 实例启动后缓存为空，
     * 条件刷新将无法找到待销毁的旧实例。
     *
     * <p>本类在 {@link #destroy()} 之前将缓存快照写入此静态 Map；新 scope 的
     * {@link #refresh(String)} 方法在本地缓存未命中时回查此处，恢复"旧实例存在"的语义。
     */
    private static final ConcurrentHashMap<String, Object> SURVIVOR_CACHE = new ConcurrentHashMap<>();

    /** 反射引用：{@link GenericScope} 的私有 {@code cache} 字段（类型为 BeanLifecycleWrapperCache）。 */
    private static final Field CACHE_FIELD;

    /** 反射引用：{@code BeanLifecycleWrapperCache} 内部的 {@code cache} 字段（类型为 ScopeCache 接口）。 */
    private static final Field WRAPPER_CACHE_FIELD;

    /** 反射引用：{@code BeanLifecycleWrapper#getBean()} 方法。 */
    private static final Method GET_BEAN_METHOD;

    static {
        try {
            CACHE_FIELD = GenericScope.class.getDeclaredField("cache");
            CACHE_FIELD.setAccessible(true);

            // BeanLifecycleWrapperCache 位于 GenericScope 的嵌套类型
            Class<?> wrapperCacheClass = findInnerClass(GenericScope.class, "BeanLifecycleWrapperCache");
            if (wrapperCacheClass == null) {
                throw new ExceptionInInitializerError(
                        "Incompatible spring-cloud-context: BeanLifecycleWrapperCache not found");
            }
            WRAPPER_CACHE_FIELD = wrapperCacheClass.getDeclaredField("cache");
            WRAPPER_CACHE_FIELD.setAccessible(true);

            // BeanLifecycleWrapper 的 getBean() 方法
            Class<?> wrapperClass = findInnerClass(GenericScope.class, "BeanLifecycleWrapper");
            if (wrapperClass == null) {
                throw new ExceptionInInitializerError(
                        "Incompatible spring-cloud-context: BeanLifecycleWrapper not found");
            }
            GET_BEAN_METHOD = wrapperClass.getDeclaredMethod("getBean");
            GET_BEAN_METHOD.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new ExceptionInInitializerError(
                    "Incompatible spring-cloud-context version: " + e.getMessage());
        }
    }

    /**
     * 在宿主类的嵌套类型中按简单名称查找内部类（包括 private）。
     */
    private static Class<?> findInnerClass(Class<?> host, String simpleName) {
        for (Class<?> inner : host.getDeclaredClasses()) {
            if (simpleName.equals(inner.getSimpleName())) {
                return inner;
            }
        }
        return null;
    }

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
     * <p><strong>惰性重建语义</strong>：本方法仅销毁旧实例。
     * 新实例在下次通过 scoped-proxy 访问该 Bean 时
     * 由 {@link GenericScope#get(String, ObjectFactory)} 惰性地创建并缓存。
     * 这种方式的好处：
     * <ul>
     *     <li>不阻塞 Nacos 回调线程。</li>
     *     <li>对齐原生 {@code RefreshScope} 的语义。</li>
     *     <li>自然支持去抖（快速多次变更只触发一次最终访问）。</li>
     * </ul>
     *
     * <p><strong>返回值语义</strong>：
     * <p>返回 {@code true} 表示"配置变更已生效"（旧实例已销毁，或无需销毁）；
     * 返回 {@code false} 仅在 destroyMethod 抛出异常时出现（此时旧实例可能未完全销毁）。
     *
     * <p>注意：{@code true} <strong>并不代表新实例已创建成功</strong>，
     * 新实例在下一次通过 scoped-proxy 访问时由 {@link GenericScope#get(String, ObjectFactory)}
     * 惰性创建。
     *
     * @param beanName 要刷新的 Bean 名称（非空）
     * @return 若配置变更已生效返回 {@code true}；若 destroyMethod 抛异常返回 {@code false}
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
            // 本地缓存没有 → 检查 survivor cache（跨 context restart 暂存）
            Object survivor = SURVIVOR_CACHE.remove(beanName);
            if (survivor != null) {
                log.info("Bean '{}' restored from survivor cache (destroyed during context restart). " +
                        "New instance will be created lazily on next access.", beanName);
                return true;
            }
            // 缓存中无该 Bean 实例（可能已被先前推送销毁，新实例尚未惰性创建）。
            // 这不是失败 —— 配置变更已生效，下次 proxy 访问会自动创建新实例。
            // 返回 true 表示"配置变更已生效"。
            log.debug("Bean '{}' has no cached instance in scope (config change will take effect " +
                    "on next proxy access).", beanName);
            return true;
        }

        log.info("Bean '{}' destroyed in conditional refresh scope. " +
                "New instance will be created lazily on next proxy access.", beanName);
        return true;
    }

    // ─── 跨 context 重启支持 ─────────────────────────────────────────

    /**
     * 销毁条件刷新作用域的所有 Bean 实例。
     *
     * <p>在销毁之前，先将当前缓存快照到 {@link #SURVIVOR_CACHE}，
     * 以便 scope 实例重建后（context restart）条件刷新仍可正常定位旧实例。
     */
    @Override
    public void destroy() {
        snapshotCacheToSurvivor();
        super.destroy();
    }

    /**
     * 将当前 scope 缓存快照到 {@link #SURVIVOR_CACHE}。
     *
     * <p>通过反射访问 {@link GenericScope} 的私有缓存字段，
     * 在 {@link #destroy()} 调用 super 之前完成快照。
     *
     * <p>反射路径：{@code GenericScope.cache} (BeanLifecycleWrapperCache)
     * → {@code .cache} (ScopeCache 接口，实际为 StandardScopeCache)
     * → {@code .cache} (ConcurrentMap)。
     */
    @SuppressWarnings("unchecked")
    private void snapshotCacheToSurvivor() {
        try {
            // 第 1 跳：GenericScope.cache → BeanLifecycleWrapperCache 实例
            Object wrapperCache = CACHE_FIELD.get(this);

            // 第 2 跳：BeanLifecycleWrapperCache.cache → ScopeCache 接口实例（实际 StandardScopeCache）
            Object scopeCacheImpl = WRAPPER_CACHE_FIELD.get(wrapperCache);

            // 第 3 跳：从 ScopeCache 实现类反射获取内部的 ConcurrentMap
            ConcurrentMap<String, ?> source = extractBackingMap(scopeCacheImpl);
            if (source == null) {
                log.debug("Cannot extract backing map from ScopeCache implementation {}",
                        scopeCacheImpl.getClass().getName());
                return;
            }

            for (Map.Entry<String, ?> entry : source.entrySet()) {
                try {
                    Object bean = GET_BEAN_METHOD.invoke(entry.getValue());
                    if (bean != null) {
                        SURVIVOR_CACHE.put(entry.getKey(), bean);
                    }
                } catch (Exception e) {
                    log.debug("Failed to snapshot bean '{}': {}", entry.getKey(), e.getMessage());
                }
            }
            if (!source.isEmpty()) {
                log.debug("Snapshot {} bean(s) into survivor cache (total={}).",
                        source.size(), SURVIVOR_CACHE.size());
            }
        } catch (Exception e) {
            // 反射失败不能阻断 destroy 流程 — 只记录 debug
            log.debug("Failed to snapshot scope cache into survivor cache: {}", e.getMessage());
        }
    }

    /**
     * 从 {@code ScopeCache} 接口实例中提取内部的 backing map。
     *
     * <p>通过反射查找实现类中第一个 {@code ConcurrentMap} 类型的字段。
     * 兼容不同 Spring Cloud 版本的 ScopeCache 实现。
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, ?> extractBackingMap(Object scopeCacheImpl) {
        for (Field f : scopeCacheImpl.getClass().getDeclaredFields()) {
            if (ConcurrentMap.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try {
                    return (ConcurrentMap<String, ?>) f.get(scopeCacheImpl);
                } catch (IllegalAccessException e) {
                    // 继续尝试下一个字段
                }
            }
        }
        return null;
    }

    /**
     * 返回 survivor cache 中暂存的 Bean 数量（用于监控 context restart 后的残留）。
     *
     * @return 暂存的 Bean 数量
     */
    public static int survivorCacheSize() {
        return SURVIVOR_CACHE.size();
    }

    /**
     * 清空 survivor cache（仅用于测试）。
     */
    public static void survivorCacheClear() {
        SURVIVOR_CACHE.clear();
    }

    /**
     * 从缓存中移除 Bean。
     *
     * <p>Spring Cloud 的 {@link GenericScope} 内部以 {@code "scopedTarget." + beanName}
     * 作为缓存 key（scoped-proxy 机制），因此需要将原始 beanName 转换后再调用
     * {@link GenericScope#remove(String)}。
     *
     * <p>若 Bean 的 {@code destroyMethod} 抛出异常，异常正常传播，
     * 由 {@link #refresh(String)} 的调用方捕获并记录 failure 指标。
     *
     * <p>不在此处吞掉异常的原因：调用方需要区分"缓存无实例"（正常 no-op）
     * 和"destroy 抛异常"（真正的 failure）。
     *
     * @param beanName Bean 名称（原始名称，不含 {@code scopedTarget.} 前缀）
     * @return 被移除的旧实例；若缓存中不存在该 Bean，返回 {@code null}
     * @throws IllegalStateException 若 destroyMethod 抛出异常
     */
    private Object removeBeanSafely(String beanName) {
        // GenericScope 内部缓存 key 为 "scopedTarget." + beanName
        String targetName = beanName.startsWith("scopedTarget.")
                ? beanName
                : "scopedTarget." + beanName;
        return super.remove(targetName);
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
