package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DailyLossLimitEvaluator implements RuleEvaluator {

    /** Policy params key for the configured maximum daily loss (USDT 估值口径;USDT-only 配置下即 USDT 数值). */
    public static final String PARAM_KEY = "maxLossUsdt";

    private static final Logger log = LoggerFactory.getLogger(DailyLossLimitEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.DAILY_LOSS_LIMIT;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        // reduce-only(平仓/减仓)skip:日损触顶后拦住退出单 = 强迫用户持有亏损仓位继续放血,
        // 与限额"停止交易"的本意相反(风控不拦退出通道)
        if (request.reduceOnly()) {
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, "reduce-only close, skip");
        }
        try {
            String maxLossStr = policy.getParams().get(PARAM_KEY);
            if (maxLossStr == null) {
                return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, PARAM_KEY + " not configured");
            }
            BigDecimal maxLoss = new BigDecimal(maxLossStr);
            BigDecimal dailyPnl = request.dailyRealizedPnl() != null ? request.dailyRealizedPnl() : BigDecimal.ZERO;

            if (dailyPnl.negate().compareTo(maxLoss) >= 0) {
                log.warn(
                        "[risk] DAILY_LOSS_LIMIT breached: dailyPnl={} maxLoss={} orderId={}",
                        dailyPnl,
                        maxLoss,
                        request.orderId());
                return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, "daily loss limit exceeded");
            }
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null);
        } catch (Exception e) {
            log.error("DailyLossLimitEvaluator internal error for order {}: {}", request.orderId(), e.getMessage(), e);
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, INTERNAL_ERROR_REASON);
        }
    }
}
