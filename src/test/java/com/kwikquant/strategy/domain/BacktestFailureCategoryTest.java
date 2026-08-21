package com.kwikquant.strategy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link BacktestFailureCategory#classify} 关键字归类全分支。 */
class BacktestFailureCategoryTest {

    @Test
    void timeoutMessages_classifyTimeout() {
        assertThat(BacktestFailureCategory.classify("backtest worker timeout"))
                .isEqualTo(BacktestFailureCategory.TIMEOUT);
        assertThat(BacktestFailureCategory.classify("worker subprocess timed out"))
                .isEqualTo(BacktestFailureCategory.TIMEOUT);
    }

    @Test
    void noMarketData_classifyMarketData() {
        assertThat(BacktestFailureCategory.classify("NO_MARKET_DATA: OKX SPOT BTC/USDT 无历史数据"))
                .isEqualTo(BacktestFailureCategory.MARKET_DATA);
        assertThat(BacktestFailureCategory.classify("回测区间无历史数据")).isEqualTo(BacktestFailureCategory.MARKET_DATA);
        assertThat(BacktestFailureCategory.classify("load_klines failed: KqApiError(code=5001)"))
                .isEqualTo(BacktestFailureCategory.MARKET_DATA);
    }

    @Test
    void envIssues_classifyEnvSetup() {
        assertThat(BacktestFailureCategory.classify(
                        "backtest worker exit -1: spawn failed: Cannot run program \"/nonexistent/python\""))
                .isEqualTo(BacktestFailureCategory.ENV_SETUP);
        assertThat(BacktestFailureCategory.classify("ModuleNotFoundError: No module named 'pandas'"))
                .isEqualTo(BacktestFailureCategory.ENV_SETUP);
        assertThat(BacktestFailureCategory.classify("backtest subprocess 未配置(kwikquant.worker.python-command 缺失)"))
                .isEqualTo(BacktestFailureCategory.ENV_SETUP);
    }

    @Test
    void codeErrors_classifyStrategyCode() {
        assertThat(BacktestFailureCategory.classify("SyntaxError: invalid syntax"))
                .isEqualTo(BacktestFailureCategory.STRATEGY_CODE);
        assertThat(BacktestFailureCategory.classify("NameError: name 'closes' is not defined"))
                .isEqualTo(BacktestFailureCategory.STRATEGY_CODE);
        assertThat(BacktestFailureCategory.classify("ZeroDivisionError: division by zero"))
                .isEqualTo(BacktestFailureCategory.STRATEGY_CODE);
    }

    @Test
    void resourceIssues_classifyQuota() {
        assertThat(BacktestFailureCategory.classify("backtest worker exit 137"))
                .isEqualTo(BacktestFailureCategory.QUOTA);
        assertThat(BacktestFailureCategory.classify("MemoryError")).isEqualTo(BacktestFailureCategory.QUOTA);
    }

    @Test
    void unknown_classifyInternal_andNullSafe() {
        assertThat(BacktestFailureCategory.classify("something weird")).isEqualTo(BacktestFailureCategory.INTERNAL);
        assertThat(BacktestFailureCategory.classify(null)).isEqualTo(BacktestFailureCategory.INTERNAL);
        assertThat(BacktestFailureCategory.classify("  ")).isEqualTo(BacktestFailureCategory.INTERNAL);
    }

    @Test
    void userMessage_knownCategories_actionableCopy_internalNull() {
        // 用户可读文案:已识别分类非空且给行动建议;INTERNAL(未识别)返 null,前端兜底透出原始 error
        for (BacktestFailureCategory c : BacktestFailureCategory.values()) {
            if (c == BacktestFailureCategory.INTERNAL) {
                assertThat(c.userMessage()).as("%s 未识别分类不带产品文案", c).isNull();
            } else {
                assertThat(c.userMessage()).as("%s 应有产品文案", c).isNotBlank();
            }
        }
        assertThat(BacktestFailureCategory.MARKET_DATA.userMessage()).contains("调整回测区间");
        assertThat(BacktestFailureCategory.STRATEGY_CODE.userMessage()).contains("修复并发布新版本");
    }
}
