package com.kwikquant.trading.domain;

/**
 * 保证金不足异常:PERP 开仓时 initialMargin 超过账户可用 quote 余额时抛出(§3.1)。
 *
 * <p>复用 {@link InsufficientBalanceException} 的语义与 ErrorCode(m9 拍板),
 * 通过子类化区分 SPOT 余额不足与 PERP 保证金不足,便于日志/审计识别场景,
 * 又不引入新 ErrorCode 造成 handler 漂移。
 *
 * <p>被 {@code TradingService.submit} catch 后转 REJECTED(同 SPOT 余额不足模式),
 * 不冒泡到 controller handler。
 */
public class InsufficientMarginException extends InsufficientBalanceException {

    public InsufficientMarginException(String message) {
        super(message);
    }

    public InsufficientMarginException(String message, Throwable cause) {
        super(message, cause);
    }
}
