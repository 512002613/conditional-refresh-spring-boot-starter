package com.liu.conditionalrefresh.exception;

/**
 * 条件刷新过程中发生的严重异常。
 *
 * <p><strong>已弃用</strong>：当前版本框架在刷新失败时不会主动抛出此异常，
 * 改为通过日志告警和 {@code conditional.refresh.failure} 指标上报。
 *
 * <p>保留此类的原因：
 * <ul>
 *     <li>将来可能引入"刷新失败快速失败"策略（如不可_RETRY 的配置格式错误）时启用</li>
 *     <li>外部监控系统可能需要捕获语义明确的异常类型</li>
 * </ul>
 *
 * <p>当前框架策略：刷新失败 → log.error → 不阻塞其他 Bean →
 * 下次访问该 Bean 时由 GenericScope 重抛 {@code BeanCreationException}。
 *
 * @author conditional-refresh
 * @since 1.0.0
 * @see com.liu.conditionalrefresh.scope.ConditionalRefreshScope#refresh(String)
 * @deprecated 当前版本未主动抛出，仅作为保留扩展点存在。
 *             未来如需启用，应在 ConditionalRefreshScope 或 ConditionalRefreshListener 中显式抛出。
 */
@Deprecated
public class RefreshFailedException extends RuntimeException {

    /** 失败的 Bean 名称。 */
    private final String beanName;

    /**
     * 构造刷新失败异常。
     *
     * @param beanName 失败的 Bean 名称
     * @param cause   原始异常
     */
    public RefreshFailedException(String beanName, Throwable cause) {
        super("Conditional refresh failed for bean '" + beanName + "': " +
              cause.getMessage(), cause);
        this.beanName = beanName;
    }

    /**
     * 获取失败的 Bean 名称。
     *
     * @return Bean 名称
     */
    public String getBeanName() {
        return beanName;
    }
}
