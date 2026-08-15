package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单节点 lease 配置(Wave 1.5-c)。
 *
 * <p>yaml:
 * <pre>{@code
 * kwikquant:
 *   lease:
 *     node-id: ${KWIKQUANT_LEASE_NODE_ID:}   # 缺省 fallback hostname
 *     stale-ms: 90000                        # 90s(heartbeat 30s × 3 未更新判崩溃)
 * }</pre>
 *
 * <p>{@code stale-ms} 必须 > heartbeat 间隔(30s)的 3 倍,容忍抖动;过小会误判活跃实例崩溃。
 */
@ConfigurationProperties(prefix = "kwikquant.lease")
public record AppLeaseProperties(String nodeId, long staleMs) {

    /** 紧凑构造器:staleMs 缺省/非正 → 90s(heartbeat 30s × 3 宽容)。 */
    public AppLeaseProperties {
        if (staleMs <= 0) {
            staleMs = 90_000;
        }
    }
}
