package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.strategy.domain.BacktestFundingDataMissingException;
import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * BacktestResultParser 分支补全(exit 2 无 stderr 的默认文案、exit 3 资金费缺失标记提取、
 * JSON 解析失败归 7300)。
 */
class BacktestResultParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exit2_nullStderr_defaultMessage() {
        assertThatThrownBy(
                        () -> BacktestResultParser.parse(new SubprocessResult(2, "", null, false, false), objectMapper))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("无历史数据");
    }

    @Test
    void exit2_extractsMarkedMessage() {
        assertThatThrownBy(() -> BacktestResultParser.parse(
                        new SubprocessResult(2, "", "NO_MARKET_DATA: OKX SPOT BTC/USDT 无数据", false, false),
                        objectMapper))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessage("OKX SPOT BTC/USDT 无数据");
    }

    @Test
    void exit3_fundingDataMissing_extractsMarkedMessage() {
        // worker 缺期检测 → exit 3 + FUNDING_DATA_MISSING: 前缀 → 7308 语义异常
        assertThatThrownBy(() -> BacktestResultParser.parse(
                        new SubprocessResult(3, "", "FUNDING_DATA_MISSING: OKX BTC/USDT 资金费序列缺期: 首缺 ...", false, false),
                        objectMapper))
                .isInstanceOf(BacktestFundingDataMissingException.class)
                .hasMessageContaining("资金费序列缺期");
    }

    @Test
    void exit3_nullStderr_defaultMessage() {
        assertThatThrownBy(
                        () -> BacktestResultParser.parse(new SubprocessResult(3, "", null, false, false), objectMapper))
                .isInstanceOf(BacktestFundingDataMissingException.class)
                .hasMessageContaining("资金费数据缺失");
    }

    @Test
    void invalidJson_throwsBacktestRunnerException() {
        assertThatThrownBy(
                        () -> BacktestResultParser.parse(SubprocessResult.of(0, "not-json", "", false), objectMapper))
                .isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void noEquityKey_zeroPnl() {
        BacktestResult r =
                BacktestResultParser.parse(SubprocessResult.of(0, "{\"trades\":[]}", "", false), objectMapper);
        assertThat(r.totalPnl()).isZero();
        assertThat(r.tradeCount()).isZero();
    }
}
