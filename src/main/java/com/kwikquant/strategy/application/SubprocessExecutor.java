package com.kwikquant.strategy.application;

import java.util.List;
import java.util.Map;

/**
 * 子进程执行 SPI。抽出来为可 mock(PythonSubprocessBacktestRunner / DockerBacktestRunner 的 subprocess
 * 调用),避开 ProcessBuilder 不可 mock 的 TDD 障碍。默认实现 {@code RealSubprocessExecutor}(infrastructure)。
 */
public interface SubprocessExecutor {

    /**
     * stdout 读取上限(64MB)。防恶意/失控策略以超大 trades/equity_curve 输出耗尽 JVM 内存——
     * ReportService 的 MAX_TRADES 校验发生在 Java 完整读入并解析 JSON 之后,来不及拦,故在读取层先截断。
     */
    long MAX_STDOUT_BYTES = 64L * 1024 * 1024;

    /**
     * 执行子进程,超时杀掉。
     *
     * @param command 命令 + 参数(List,如 ["python","worker_server.py","--mode=backtest"])
     * @param env 环境变量白名单(子进程不继承宿主环境)
     * @param stdinPayload 写入子进程 stdin 后关闭(null = 不写,直接关闭);用于 docker run -i 配置下发,
     *     避开 argv+env 总量 ~128KB 内核上限(策略源码可达 1MB)与 env 经 docker inspect 可窥的问题
     * @param timeoutSec 超时秒(超时 destroyForcibly)
     * @return 结果(exitCode/stdout/stderr/timedOut/stdoutTruncated);stdout 超 {@link #MAX_STDOUT_BYTES} 截断
     */
    SubprocessResult run(List<String> command, Map<String, String> env, String stdinPayload, long timeoutSec);
}
