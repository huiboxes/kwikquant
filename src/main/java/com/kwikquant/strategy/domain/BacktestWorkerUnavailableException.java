package com.kwikquant.strategy.domain;

/**
 * 回测 worker 环境不可用(启动自检失败:解释器缺失/依赖不完整)。
 * 提交回测前置拒绝,ErrorCode 7305,HTTP 503。
 */
public class BacktestWorkerUnavailableException extends RuntimeException {

    public BacktestWorkerUnavailableException(String detail) {
        super(detail);
    }
}
