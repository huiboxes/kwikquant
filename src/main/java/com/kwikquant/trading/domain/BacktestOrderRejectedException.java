package com.kwikquant.trading.domain;

/**
 * 回测下单被拒(虚拟账本现金/币库不足)。映射 400 + ErrorCode 7302(§3.1)。
 */
public class BacktestOrderRejectedException extends RuntimeException {

    public BacktestOrderRejectedException(String message) {
        super(message);
    }
}
