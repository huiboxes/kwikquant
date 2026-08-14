package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测 Runner 之一(实现 {@link BacktestRunner} SPI):app 进程内直接起 python 子进程
 * ({@code python worker_server.py --mode=backtest}),env 注入 TASK_CONFIG_JSON + WORKER_SERVICE_TOKEN。
 *
 * <p><b>安全边界</b>:子进程与 app 同容器同 UID,/proc 可读宿主进程 env 中的平台密钥——
 * 仅用于 dev/test 等受信环境({@code kwikquant.backtest.runner=subprocess},缺省值)。
 * prod 必须用 {@code DockerBacktestRunner}(独立隔离容器,见 {@code kwikquant.backtest.runner=docker})。
 *
 * <p>结果解析委托 {@link BacktestResultParser}(stdout section8 协议,exit 2 = 无数据 7304)。
 */
@Component
@ConditionalOnProperty(name = "kwikquant.backtest.runner", havingValue = "subprocess", matchIfMissing = true)
public class PythonSubprocessBacktestRunner implements BacktestRunner {

    private final SubprocessExecutor executor;
    private final ObjectMapper objectMapper;
    private final String pythonCommand;
    private final String workerScript;
    private final String apiBase;
    private final long timeoutSec;

    public PythonSubprocessBacktestRunner(
            SubprocessExecutor executor,
            ObjectMapper objectMapper,
            // dev/test 回测:宿主 python venv + 相对路径脚本(application-dev/test.yaml)。
            // 三属性保留 :空 默认仅为未配置 profile 兜底(bean 可实例化),未配置时 run() fail-closed 拒绝。
            @Value("${kwikquant.worker.python-command:}") String pythonCommand,
            @Value("${kwikquant.worker.script:}") String workerScript,
            @Value("${kwikquant.worker.api-base:}") String apiBase,
            @Value("${kwikquant.worker.timeout-sec:3600}") long timeoutSec) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.pythonCommand = pythonCommand;
        this.workerScript = workerScript;
        this.apiBase = apiBase;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public BacktestResult run(BacktestRunRequest request) {
        if (pythonCommand == null
                || pythonCommand.isBlank()
                || workerScript == null
                || workerScript.isBlank()
                || apiBase == null
                || apiBase.isBlank()) {
            throw new BacktestRunnerException(
                    "backtest subprocess 未配置(kwikquant.worker.python-command/script/api-base 缺失;"
                            + "检查当前 profile 的 kwikquant.worker 配置)");
        }
        String taskConfig = objectMapper.writeValueAsString(request);
        Map<String, String> env = new java.util.HashMap<>();
        env.put("TASK_CONFIG_JSON", taskConfig);
        env.put("WORKER_SERVICE_TOKEN", request.serviceToken() == null ? "" : request.serviceToken());
        env.put("KWIKQUANT_API_BASE", apiBase);
        List<String> command = List.of(pythonCommand, workerScript, "--mode=backtest");
        SubprocessResult result = executor.run(command, env, null, timeoutSec);
        if (result.timedOut()) {
            throw new BacktestRunnerException("worker subprocess timeout (>" + timeoutSec + "s)");
        }
        return BacktestResultParser.parse(result, objectMapper);
    }
}
