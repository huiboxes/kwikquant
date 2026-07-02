package com.kwikquant.strategy.domain;

/**
 * 回测子进程执行失败(Popen 失败/超时/非零退出)。Java 内部异常**不返回 HTTP**(§1.2/§1.3),
 * 由 BacktestExecutionGateway catch → markFailed + WS FAILED。ErrorCode 7300。
 */
public class BacktestRunnerException extends RuntimeException {

    public BacktestRunnerException(String message) {
        super(message);
    }

    public BacktestRunnerException(String message, Throwable cause) {
        super(message, cause);
    }
}
