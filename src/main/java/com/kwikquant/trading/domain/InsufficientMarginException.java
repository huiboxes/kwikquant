package com.kwikquant.trading.domain;

/**
 * 保证金不足异常:PERP 开仓时 initialMargin 超过账户可用 quote 余额时抛出(§3.1)。
 *
 * <p>继承 {@code account.domain.InsufficientBalanceException}(m9 拍板:复用 ErrorCode + submit catch 链),
 * <strong>不是</strong>同包的 {@code trading.domain.InsufficientBalanceException}(后者冒泡到
 * {@code TradingExceptionHandler} controller handler,语义不同)。用全限定 extends 避免同包默认解析歧义
 * (2a 原 bug:同包默认解析选错,导致 submit catch 捕获失效、会冒泡 500)。
 *
 * <p>被 {@code TradingService.submit} 的 {@code catch (InsufficientBalanceException | InvalidOrderException)}
 * 捕获后转 REJECTED(同 SPOT 余额不足模式,ErrorCode 4102),不冒泡到 controller handler。
 * trading 模块依赖 account 可直接引用此 account.domain 父类。
 */
public class InsufficientMarginException extends com.kwikquant.account.domain.InsufficientBalanceException {

    public InsufficientMarginException(String message) {
        super(message);
    }

    public InsufficientMarginException(String message, Throwable cause) {
        super(message, cause);
    }
}
