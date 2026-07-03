package com.liu.conditionalrefresh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 条件刷新的配置属性。
 *
 * <p>通过 {@link ConfigurationProperties} 绑定 {@code conditional.refresh.} 前缀的配置项。
 *
 * <h3>配置示例</h3>
 * <pre>{@code
 * # application.yml
 * conditional:
 *   refresh:
 *     enabled: true               # 是否启用条件刷新（默认 true）
 *     debounce:
 *       delay: 500                # 去抖静默窗口（ms），默认 500
 *     monitor:
 *       metrics-enabled: true     # 是否暴露 Micrometer 指标（默认 true）
 * }</pre>
 *
 * @author conditional-refresh
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "conditional.refresh")
public class ConditionalRefreshProperties {

    /**
     * 是否启用条件刷新功能。
     *
     * <p>默认 {@code true}。设为 {@code false} 后，所有 {@code @RefreshOnKeys} 标记的 Bean
     * 将退化为普通单例（不监听、不刷新）。
     */
    private boolean enabled = true;

    /**
     * 去抖窗口大小（毫秒）。
     *
     * <p>当同一 Bean 在静默窗口内收到多次刷新通知时，只执行最后一次。
     * 默认 500ms。
     */
    private long debounceDelay = 500;

    /**
     * 是否暴露 Micrometer 监控指标。
     *
     * <p>默认 {@code true}。指标包括：
     * <ul>
     *     <li>{@code conditional.refresh.success}：刷新成功次数。</li>
     *     <li>{@code conditional.refresh.failure}：刷新失败次数。</li>
     * </ul>
     */
    private boolean metricsEnabled = true;

    /**
     * 是否启用监听器初始快照。
     *
     * <p>若 {@code true}，在注册 Nacos 监听器前主动获取一次当前配置作为初始快照，
     * 避免首次推送的全量刷新。默认 {@code true}。
     */
    private boolean initialSnapshotEnabled = true;

    // ─── Getter / Setter ─────────────────────────────────────────────

    /**
     * 获取条件刷新的全局开关。
     *
     * @return 若启用条件刷新返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置条件刷新的全局开关。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取去抖窗口大小（毫秒）。
     *
     * @return 去抖静默时长（ms）
     */
    public long getDebounceDelay() {
        return debounceDelay;
    }

    /**
     * 设置去抖窗口大小（毫秒）。
     *
     * @param debounceDelay 去抖静默时长（ms）
     */
    public void setDebounceDelay(long debounceDelay) {
        this.debounceDelay = debounceDelay;
    }

    /**
     * 是否暴露 Micrometer 监控指标。
     *
     * @return 若启用指标暴露返回 {@code true}
     */
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * 设置是否暴露 Micrometer 监控指标。
     *
     * @param metricsEnabled 是否启用指标暴露
     */
    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    /**
     * 获取是否启用监听器初始快照。
     *
     * @return 若启用初始快照返回 {@code true}
     */
    public boolean isInitialSnapshotEnabled() {
        return initialSnapshotEnabled;
    }

    /**
     * 设置是否启用监听器初始快照。
     *
     * <p>若启用，在注册 Nacos 监听器前主动获取一次当前配置，避免首次推送的全量刷新。
     *
     * @param initialSnapshotEnabled 是否启用初始快照
     */
    public void setInitialSnapshotEnabled(boolean initialSnapshotEnabled) {
        this.initialSnapshotEnabled = initialSnapshotEnabled;
    }

    @Override
    public String toString() {
        return "ConditionalRefreshProperties{" +
               "enabled=" + enabled +
               ", debounceDelay=" + debounceDelay +
               ", metricsEnabled=" + metricsEnabled +
               ", initialSnapshotEnabled=" + initialSnapshotEnabled +
               '}';
    }
}
