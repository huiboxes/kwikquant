package com.kwikquant.trading.domain;

/**
 * 回测 task 不在 RUNNING 状态收 order(账本不存在)。映射 409 + ErrorCode 7303(§3.1)。
 *
 * <p>Worker 收 409 后 exit 0(正常结束),Java PythonSubprocessBacktestRunner 检测 exit 0 查 task 状态防重复。
 */
public class BacktestTaskNotRunningException extends RuntimeException {

    public BacktestTaskNotRunningException(String message) {
        super(message);
    }
}
