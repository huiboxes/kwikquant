package com.kwikquant.risk.domain.evaluators;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyLossLimitEvaluatorTest {

    private final DailyLossLimitEvaluator evaluator = new DailyLossLimitEvaluator();

    @Test
    void supportedType() {
        assertThat(evaluator.supportedType()).isEqualTo(RiskRuleType.DAILY_LOSS_LIMIT);
    }

    @Test
    void withinLimit_passes() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                new BigDecimal("-3000"),
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void exceedsLimit_rejects() {
        // dailyRealizedPnl 来自 fills.realized_pnl_delta 汇总(平仓 PnL)。当日平仓亏损 -6000 > maxLoss 5000 → 拒。
        // trading-H5:旧 sumNetCashflow 口径下 PERP OPEN_SHORT side=SELL 虚高 dailyPnl,亏损时永不触发漏拦。
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                new BigDecimal("-6000"),
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("daily loss limit exceeded");
    }

    @Test
    void missingMaxLossParam_rejects() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of());

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                0,
                BigDecimal.ZERO,
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("maxLossUsdt not configured");
    }

    @Test
    void nullDailyPnl_treatedAsZero_passes() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                0,
                null,
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void openPositionOnly_notRejectedEvenIfMaxLossSmall() {
        // trading-H5:开仓 BUY fill realized_pnl_delta=0(非旧净现金流 -price*qty),故当日只有开仓时
        // dailyRealizedPnl=0,DAILY_LOSS_LIMIT 不误拦——即使开仓支出额(1 BTC @40000=40000 USDT)
        // 远超 maxLoss(100),因 realized_pnl_delta=0,dailyPnl.negate()=0 < 100 → pass。
        // 旧 sumNetCashflow 口径下此场景 dailyPnl=-40000,误拦开仓。
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "100"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("1"),
                new BigDecimal("40000"),
                new BigDecimal("40000"),
                0,
                BigDecimal.ZERO,
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-open");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void malformedMaxLossParam_failClosedInternalError() {
        // maxLossUsdt="abc" → new BigDecimal 抛 NumberFormatException → catch(Exception) →
        // fail-closed INTERNAL_ERROR_REASON(风控安全:解析失败宁可误拦不漏拦)
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "abc"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                new BigDecimal("-3000"),
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo(RuleEvaluator.INTERNAL_ERROR_REASON);
    }
}
