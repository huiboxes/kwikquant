package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether the daily loss limit has been exceeded.
 *
 * <p>v1 stub: always passes. Full implementation requires historical P&amp;L tracking.
 * Registered as a Spring bean via {@code RiskConfig}.
 */
public class DailyLossLimitEvaluator implements RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DailyLossLimitEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.DAILY_LOSS_LIMIT;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        try {
            log.warn("DAILY_LOSS_LIMIT not yet implemented, passing by default");
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null);
        } catch (Exception e) {
            log.error(
                    "DailyLossLimitEvaluator internal error for order {}: {}",
                    request.orderId(),
                    e.getMessage(),
                    e);
            return new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, false, "internal evaluator error");
        }
    }
}
