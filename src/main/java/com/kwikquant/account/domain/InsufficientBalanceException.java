package com.kwikquant.account.domain;

/**
 * 余额不足异常。模拟盘挂单冻结时 free 余额不够抛出。
 *
 * <p>被 {@code TradingService.submit} catch 后转 REJECTED(同 RiskGate 拒单模式),
 * 不冒泡到 controller handler。trading 模块依赖 account 可直接 import 此类。
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }

    public InsufficientBalanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
