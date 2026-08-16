package com.kwikquant.shared.infra;

/**
 * 启动时检测到活跃 lease 被另一实例持有 → 启动失败(单节点不变量)。
 *
 * <p>非 REST 场景(启动期),不映射 ErrorCode;unchecked,让 Spring Boot 启动失败 exit 1。
 * message 含 holder node_id + last_seen 供排障。
 */
public class ActiveLeaseHeldException extends RuntimeException {

    private final String holderNodeId;

    public ActiveLeaseHeldException(String holderNodeId, String detail) {
        super("active lease held by node '" + holderNodeId + "'; refusing startup (single-node invariant). " + detail);
        this.holderNodeId = holderNodeId;
    }

    public String holderNodeId() {
        return holderNodeId;
    }
}
