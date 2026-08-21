package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

/**
 * Worker bootstrap 拉取配置时,Java 端 config registry 无此 strategyId。
 *
 * <p>语义:worker 持有效 RUNNER token(WorkerTokenFilter 验证通过),但该 strategy 已停/markError/
 * 重启竞态,configRegistry 已 remove。worker 收 7307 → exit 1(strategy 已停,不必拉配置)。
 * 与 7301(token 无效)区分:这里 token 有效,只是 config 不在运行 registry。
 */
public class WorkerConfigUnavailableException extends ResourceNotFoundException {
    private final long strategyId;

    public WorkerConfigUnavailableException(long strategyId) {
        super("WorkerConfig", strategyId);
        this.strategyId = strategyId;
    }

    public long strategyId() {
        return strategyId;
    }
}
