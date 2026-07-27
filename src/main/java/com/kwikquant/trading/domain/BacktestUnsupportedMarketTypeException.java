package com.kwikquant.trading.domain;

/**
 * 回测不支持该市场类型(当前仅支持 SPOT)。映射 400 + ErrorCode 7305。
 *
 * <p>回测 PERP(合约)暂未支持——{@link com.kwikquant.trading.application.BacktestLedger} 未扩保证金桶/
 * 强平/资金费率,Worker 推 PERP 单时 Java 立即拒,避免回测结果与实盘语义偏离。
 */
public class BacktestUnsupportedMarketTypeException extends RuntimeException {

    public BacktestUnsupportedMarketTypeException(String message) {
        super(message);
    }
}
