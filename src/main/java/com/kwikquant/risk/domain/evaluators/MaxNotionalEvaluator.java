package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether the notional value of an order exceeds the configured maximum.
 *
 * <p>The policy params must contain {@code maxNotionalUsdt} as a positive decimal string.
 * Registered as a Spring bean via {@code RiskConfig}.
 *
 * <p>阶段2h(§11 M8-new):对 {@code marketType=PERP} 跳过(返 passed=true)——PERP 高杠杆下 notional
 * 远超 SPOT 阈值会系统性拒单,PERP 保证金占用改由 {@link MaxInitialMarginEvaluator} 独立规则覆盖
 * (initialMargin = notional / leverage &lt;= availableMargin × ratio)。SPOT 走原 notional 逻辑不变。
 */
public class MaxNotionalEvaluator implements RuleEvaluator {

    /** Policy params key for the configured maximum notional (USDT 估值口径;USDT-only 配置下 notional 即 symbol quote=USDT 数值,语义一致). */
    public static final String PARAM_KEY = "maxNotionalUsdt";

    private static final Logger log = LoggerFactory.getLogger(MaxNotionalEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.MAX_NOTIONAL;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        // §11 M8-new:PERP 跳过(交 MaxInitialMarginEvaluator 覆盖),避免高杠杆系统性拒单
        if (request.marketType() == MarketType.PERP) {
            return new RuleResult(RiskRuleType.MAX_NOTIONAL, true, "PERP skipped — covered by MAX_INITIAL_MARGIN");
        }
        try {
            if (request.notionalValue() == null) {
                return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional value unavailable");
            }

            String maxStr = policy.getParams().get(PARAM_KEY);
            BigDecimal maxNotionalUsdt = new BigDecimal(maxStr);

            if (request.notionalValue().compareTo(maxNotionalUsdt) > 0) {
                String reason = String.format(
                        "notional %s USDT exceeds max %s USDT",
                        request.notionalValue().toPlainString(), maxNotionalUsdt.toPlainString());
                return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, reason);
            }

            return new RuleResult(RiskRuleType.MAX_NOTIONAL, true, null);
        } catch (Exception e) {
            log.error("MaxNotionalEvaluator internal error for order {}: {}", request.orderId(), e.getMessage(), e);
            return new RuleResult(RiskRuleType.MAX_NOTIONAL, false, INTERNAL_ERROR_REASON);
        }
    }
}
