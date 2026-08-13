package com.kwikquant.shared.infra;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Worker service token 注册表(内存)。Worker→Java REST 认证凭证({@code X-Worker-Token} header)。
 *
 * <p>归 shared::infra(review M1 修复):trading({@code WorkerTokenFilter} 验 token)与 strategy(BEG/WOS
 * 签发 token)都需调,放 trading 会违反"strategy 不依赖 trading"。reissueForRunningStrategies 不在此层
 * (shared 不能依赖 strategy),由 {@code WorkerOrchestratorService.reconcileRunningStrategies} 调
 * {@link #issueRunnerToken} per RUNNING strategy。
 *
 * <p>RUNNER token 按 strategyId 唯一，BACKTEST token 按 taskId 唯一。两类 token 生命周期完全隔离；同一策略可同时运行
 * runner 和多个回测。不持久化,应用重启丢失,reconcile 重发。
 */
@Component
public class WorkerTokenService {

    /** Worker token taskType：回测任务。 */
    public static final String TASK_TYPE_BACKTEST = "BACKTEST";

    /** Worker token taskType：实盘/模拟盘策略运行。 */
    public static final String TASK_TYPE_RUNNER = "RUNNER";

    private final ConcurrentHashMap<String, WorkerTokenEntry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> runnerIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> backtestIndex = new ConcurrentHashMap<>();

    /**
     * 生成 RUNNER token 绑 strategyId+userId+exchange+accountId。同 strategyId 重发时失效旧 RUNNER token。
     *
     * <p>accountId 绑 token(start 时验属 user),WorkerTokenFilter 注入 WORKER_ACCOUNT_ID_ATTR,
     * OrderController/PositionController 用 accountId 下单/查持仓(不再靠 exchange 推导,去 UNIQUE 前提)。
     */
    public String issueRunnerToken(long strategyId, long userId, String exchange, long accountId) {
        return issueToken(strategyId, null, TASK_TYPE_RUNNER, userId, exchange, accountId, runnerIndex, strategyId);
    }

    /** 生成绑定到单个回测 taskId 的 token；同 task 重发只失效该 task 的旧 token。 */
    public String issueBacktestToken(long strategyId, long taskId, long userId, String exchange) {
        return issueToken(strategyId, taskId, TASK_TYPE_BACKTEST, userId, exchange, 0L, backtestIndex, taskId);
    }

    private String issueToken(
            long strategyId,
            Long taskId,
            String taskType,
            long userId,
            String exchange,
            long accountId,
            ConcurrentHashMap<Long, String> index,
            long indexKey) {
        String token = UUID.randomUUID().toString();
        WorkerTokenEntry entry =
                new WorkerTokenEntry(strategyId, taskId, taskType, userId, exchange, accountId, Instant.now());
        index.compute(indexKey, (key, oldToken) -> {
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
            if (TASK_TYPE_RUNNER.equals(entry.taskType())) {
                runnerIndex.remove(entry.strategyId(), token);
            } else if (entry.taskId() != null) {
                backtestIndex.remove(entry.taskId(), token);
            }
        }
    }

    /**
     * 通过 strategyId 失效 RUNNER token(WOS.stopWorker/handleUnhealthy 用)，不影响 BACKTEST token。
     * 幂等,未 issue 过 token 返回 false。
     */
    public boolean revokeRunnerTokenForStrategy(long strategyId) {
        String token = runnerIndex.remove(strategyId);
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

    public record WorkerTokenEntry(
            long strategyId,
            Long taskId,
            String taskType,
            long userId,
            String exchange,
            long accountId,
            Instant issuedAt) {}
}
