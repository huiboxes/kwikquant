package com.kwikquant.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行情订阅"WS 优先、失败降级 REST 轮询"兜底配置。
 *
 * <p>在只通 REST 的受限网络下,CCXT WebSocket({@code watchTicker}/{@code watchOHLCV})持续失败,
 * 行情订阅不能就此停摆:连续失败 {@code wsFallbackAfterFailures} 次后,worker 自动降级为按
 * {@code restPollInterval} 周期调 REST({@code fetchTicker}/{@code fetchOHLCV})轮询,并经与 WS 相同的
 * 回调下发,消费方(模拟盘撮合/前端推送)零感知;降级期间每隔 {@code wsRetryInterval} 探测一次 WS,
 * 探测成功即回到 WS 模式。
 */
@ConfigurationProperties(prefix = "kwikquant.market.fallback")
public record MarketFallbackProperties(
        Duration restPollInterval, Integer wsFallbackAfterFailures, Duration wsRetryInterval) {

    /** REST 轮询周期默认值。 */
    public static final Duration DEFAULT_REST_POLL_INTERVAL = Duration.ofSeconds(5);
    /** 连续 WS 失败多少次后降级,默认值。 */
    public static final int DEFAULT_WS_FALLBACK_AFTER_FAILURES = 3;
    /** 降级期间多久探测一次 WS 是否恢复,默认值。 */
    public static final Duration DEFAULT_WS_RETRY_INTERVAL = Duration.ofSeconds(60);

    public MarketFallbackProperties {
        if (restPollInterval == null) restPollInterval = DEFAULT_REST_POLL_INTERVAL;
        if (wsFallbackAfterFailures == null) wsFallbackAfterFailures = DEFAULT_WS_FALLBACK_AFTER_FAILURES;
        if (wsRetryInterval == null) wsRetryInterval = DEFAULT_WS_RETRY_INTERVAL;
        // 下界钳制:误配负/零值会让 worker 线程 sleep 抛错静默死亡或紧循环轰击 REST,回退默认
        if (restPollInterval.isNegative() || restPollInterval.isZero()) restPollInterval = DEFAULT_REST_POLL_INTERVAL;
        if (wsFallbackAfterFailures < 1) wsFallbackAfterFailures = DEFAULT_WS_FALLBACK_AFTER_FAILURES;
        if (wsRetryInterval.isNegative() || wsRetryInterval.isZero()) wsRetryInterval = DEFAULT_WS_RETRY_INTERVAL;
    }
}
