package com.kwikquant.shared.infra;

import java.time.OffsetDateTime;

/**
 * app_lease 单行记录视图(供启动拒绝日志:报哪个 node 持有活跃 lease)。只读视图,
 * {@code map-underscore-to-camel-case} 自动映射(node_id→nodeId 等)。
 */
public record AppLeaseRow(String nodeId, OffsetDateTime acquiredAt, OffsetDateTime lastSeenAt) {}
