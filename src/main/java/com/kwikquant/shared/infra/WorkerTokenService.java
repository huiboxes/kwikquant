package com.kwikquant.shared.infra;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Worker service token 注册表(内存)。Worker→Java REST 认证凭证({@code X-Worker-Token} header)。
 *
 * <p>归 shared::infra(review M1 修复):trading({@code WorkerTokenFilter} 验 token)与 strategy(BEG/WOS
 * issueToken)都需调,放 trading 会违反"strategy 不依赖 trading"。reissueForRunningStrategies 不在此层
 * (shared 不能依赖 strategy),由 {@code WorkerOrchestratorService.reconcileRunningStrategies} 调
 * {@link #issueToken} per RUNNING strategy。
 *
 * <p>token 绑 strategyId+taskType(BACKTEST/RUNNER)。{@link #issueToken} 对同一 strategyId 重发时失效旧 token
 * (reissue 语义)。不持久化(§5 service token 不持久化决策),应用重启丢失,reconcile 重发。
 */
@Component
public class WorkerTokenService {

    private final ConcurrentHashMap<String, WorkerTokenEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> reverseIndex = new ConcurrentHashMap<>();

    /**
     * 生成 token 绑 strategyId+taskType+userId+exchange,入 registry。同 strategyId 重发时失效旧 token
     * (reissue 语义)。
     *
     * <p>Wave 8 §3.7 R4 修复:token entry 携带 userId+exchange,让 {@code WorkerTokenFilter} 无需跨模块
     * 查 strategy 表即可注入 account 推导链所需 attr。
     */
    public String issueToken(long strategyId, String taskType, long userId, String exchange) {
        String token = UUID.randomUUID().toString();
        WorkerTokenEntry entry = new WorkerTokenEntry(strategyId, taskType, userId, exchange, Instant.now());
        reverseIndex.compute(strategyId, (sid, oldToken) -> {
            if (oldToken != null) {
                registry.remove(oldToken);
            }
            registry.put(token, entry);
            return token;
        });
        return token;
    }

    /** 验 token 有效且 strategyId 匹配。null/blank token 返回 false。 */
    public boolean validateToken(String token, long strategyId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        WorkerTokenEntry entry = registry.get(token);
        return entry != null && entry.strategyId() == strategyId;
    }

    /** 失效 token。未知 token noop(幂等)。 */
    public void revokeToken(String token) {
        if (token == null) {
            return;
        }
        WorkerTokenEntry entry = registry.remove(token);
        if (entry != null) {
            reverseIndex.remove(entry.strategyId(), token);
        }
    }

    /**
     * 通过 strategyId 失效 token(WOS.stopWorker/handleUnhealthy 用,§3.7)。
     * 幂等,未 issue 过 token 返回 false。
     */
    public boolean revokeTokenForStrategy(long strategyId) {
        String token = reverseIndex.remove(strategyId);
        if (token == null) {
            return false;
        }
        registry.remove(token);
        return true;
    }

    /** 取 token 对应 entry;filter 用此从 token 得 strategyId(无需 path 反查 taskId→strategyId,避开跨模块依赖)。无效返回 null。 */
    public WorkerTokenEntry getEntry(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return registry.get(token);
    }

    public record WorkerTokenEntry(long strategyId, String taskType, long userId, String exchange, Instant issuedAt) {}
}
