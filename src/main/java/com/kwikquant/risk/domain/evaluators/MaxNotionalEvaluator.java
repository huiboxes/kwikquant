package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether the notional value of an order exceeds the configured maximum.
 *
 * <p>The policy params must contain {@code maxNotionalUsdt} as a positive decimal string.
 * Registered as a Spring bean via {@code RiskConfig}.
 */
public class MaxNotionalEvaluator implements RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MaxNotionalEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.MAX_NOTIONAL;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        try {
            if (request.notionalValue() == null) {
                return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional value unavailable");
            }

            String maxStr = policy.getParams().get("maxNotionalUsdt");
            BigDecimal maxNotionalUsdt = new BigDecimal(maxStr);

            if (request.notionalValue().compareTo(maxNotionalUsdt) > 0) {
                String reason = String.format(
                        "notional %s USDT exceeds max %s USDT", request.notionalValue().toPlainString(),
                        maxNotionalUsdt.toPlainString());
                return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, reason);
            }

            return new RuleResult(RiskRuleType.MAX_NOTIONAL, true, null);
        } catch (Exception e) {
            log.error("MaxNotionalEvaluator internal error for order {}: {}", request.orderId(), e.getMessage(), e);
            return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "internal evaluator error");
        }
    }
}
