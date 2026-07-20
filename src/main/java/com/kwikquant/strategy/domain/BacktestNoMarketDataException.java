package com.kwikquant.strategy.domain;

/**
 * 回测区间无历史数据(worker 拉空 → exit 2)。Java 内部异常**不返回 HTTP**(语义同
 * {@link BacktestRunnerException}),由 BacktestExecutionGateway catch → markFailed +
 * WS FAILED,ErrorCode {@code 7304 BACKTEST_NO_MARKET_DATA}。message 含区间/exchange/
 * symbol/interval,供前端展示"区间 ... 无历史数据"。
 */
public class BacktestNoMarketDataException extends RuntimeException {

    public BacktestNoMarketDataException(String message) {
        super(message);
    }
}
