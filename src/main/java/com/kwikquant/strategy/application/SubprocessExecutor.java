package com.kwikquant.strategy.application;

import java.util.List;
import java.util.Map;

/**
 * 子进程执行 SPI(§3.6)。抽出来为可 mock(PythonSubprocessBacktestRunner 的 subprocess 调用),
 * 避开 ProcessBuilder 不可 mock 的 TDD 障碍。默认实现 {@code RealSubprocessExecutor}(infrastructure)。
 */
@FunctionalInterface
public interface SubprocessExecutor {

    /**
     * 执行子进程,超时杀掉。
     *
     * @param command 命令 + 参数(List,如 ["python","worker_server.py","--mode=backtest"])
     * @param env 环境变量(merge 到当前进程 env)
     * @param timeoutSec 超时秒(超时 destroyForcibly)
     * @return 结果(exitCode/stdout/stderr/timedOut)
     */
    SubprocessResult run(List<String> command, Map<String, String> env, long timeoutSec);
}
