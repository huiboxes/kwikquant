package com.kwikquant.strategy.domain;

/**
 * PERP 回测资金费数据运行期缺失(worker 缺期检测 → stderr {@code FUNDING_DATA_MISSING:} +
 * exit 3)。提交/执行预检(FundingCoverageGuard)已双卡点,本异常兜"预检后数据被删/序列
 * 相邻缺期"的运行期异常态——绝不静默漏收(docs/perp-backtest-spec.md §5.7)。
 *
 * <p>Java 内部异常不返回 HTTP(语义同 {@link BacktestNoMarketDataException}),由
 * BacktestExecutionGateway catch → markFailed + WS FAILED(分类 MARKET_DATA),
 * ErrorCode {@code 7308 BACKTEST_FUNDING_DATA_MISSING}。
 */
public class BacktestFundingDataMissingException extends RuntimeException {

    public BacktestFundingDataMissingException(String message) {
        super(message);
    }
}
