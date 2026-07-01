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

    private static final Logger log = LoggerFactory.getLogger(DailyLossLimitEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.DAILY_LOSS_LIMIT;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        try {
            String maxLossStr = policy.getParams().get("maxLossUsdt");
            if (maxLossStr == null) {
                return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, "maxLossUsdt not configured");
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
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, "internal evaluator error");
        }
    }
}
