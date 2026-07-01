package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether the order frequency in the configured 1-minute window has been exceeded.
 *
 * <p>The policy params must contain {@code maxPerMinute} as a positive integer. The count of
 * orders submitted by the account in the last 60s (including the current order) is provided
 * in {@link RiskCheckRequest#recentOrderCount()}; the rule rejects when
 * {@code recentOrderCount > maxPerMinute}. Registered as a Spring bean via {@code RiskConfig}.
 */
public class OrderFrequencyEvaluator implements RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(OrderFrequencyEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.ORDER_FREQUENCY;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        try {
            String maxStr = policy.getParams().get("maxPerMinute");
            int maxPerMinute = Integer.parseInt(maxStr);

            if (request.recentOrderCount() > maxPerMinute) {
                return new RuleResult(
                        RiskRuleType.ORDER_FREQUENCY,
                        false,
                        "order frequency " + request.recentOrderCount() + "/min exceeds max " + maxPerMinute + "/min");
            }
            return new RuleResult(RiskRuleType.ORDER_FREQUENCY, true, null);
        } catch (Exception e) {
            log.error(
                    "OrderFrequencyEvaluator internal error for order {}: {}",
                    request.orderId(),
                    e.getMessage(),
                    e);
            return new RuleResult(RiskRuleType.ORDER_FREQUENCY, false, "internal evaluator error");
        }
    }
}
