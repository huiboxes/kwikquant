package com.kwikquant.risk.domain.evaluators;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评估 PERP 订单初始保证金占用是否超过可用余额 × 配置比例(阶段2h §10 M8/§11 M8-new)。
 *
 * <p>公式:{@code initialMargin = notionalValue / leverage}(逐仓 initialMargin,与 PositionService
 * applyPerpDelta 的 frozenAmount 增量同口径)。校验 {@code initialMargin &lt;= availableMargin × ratio}。
 *
 * <p>policy params key {@code maxInitialMarginRatio}(0-1 小数,默认 {@link #DEFAULT_MAX_INITIAL_MARGIN_RATIO}=0.8
 * 留 20% 缓冲,§12 m1-s)。
 *
 * <p>对 SPOT 请求 skip(返 passed=true,"not PERP")——SPOT 无 initialMargin 概念,即使误配该 policy
 * 也不拦 SPOT 单。PERP 请求缺 notional/leverage/availableMargin 任一 → fail-closed(passed=false),
 * 避免风控逃逸(宁可误拦不漏拦)。
 *
 * <p>注册为 Spring bean via {@code RiskConfig}。
 */
public class MaxInitialMarginEvaluator implements RuleEvaluator {

    /** Policy params key for the configured max initial margin ratio (0-1, e.g. 0.8 = 80%). */
    public static final String PARAM_KEY = "maxInitialMarginRatio";

    /**
     * 默认最大初始保证金比例(§12 m1-s:80%,留 20% 缓冲)。用于 RiskService 后置兜底——PERP 请求
     * 且账户无 MAX_INITIAL_MARGIN policy 时,用此默认比例评一次(fail-closed,不 auto-approve PERP)。
     * 等价"每账户隐式 80% policy",避免 per-account risk_policies 表全局 seed 架构问题。
     */
    public static final BigDecimal DEFAULT_MAX_INITIAL_MARGIN_RATIO = new BigDecimal("0.8");

    private static final Logger log = LoggerFactory.getLogger(MaxInitialMarginEvaluator.class);

    @Override
    public RiskRuleType supportedType() {
        return RiskRuleType.MAX_INITIAL_MARGIN;
    }

    @Override
    public RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request) {
        // SPOT skip——无 initialMargin 概念
        if (request.marketType() != MarketType.PERP) {
            return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, true, "not PERP, skip");
        }
        try {
            if (request.notionalValue() == null) {
                return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, false, "notional value unavailable");
            }
            Integer leverage = request.leverage();
            if (leverage == null || leverage <= 0) {
                return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, false, "leverage unavailable or invalid");
            }
            BigDecimal availableMargin = request.availableMargin();
            if (availableMargin == null) {
                return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, false, "available margin unavailable");
            }

            BigDecimal ratio = resolveRatio(policy);
            BigDecimal initialMargin =
                    request.notionalValue().divide(new BigDecimal(leverage), 8, RoundingMode.HALF_UP);
            BigDecimal threshold = availableMargin.multiply(ratio);
            if (initialMargin.compareTo(threshold) > 0) {
                String reason = String.format(
                        "initial margin %s (notional %s / leverage %d) exceeds %s (available %s × ratio %s)",
                        initialMargin.toPlainString(),
                        request.notionalValue().toPlainString(),
                        leverage,
                        threshold.toPlainString(),
                        availableMargin.toPlainString(),
                        ratio.toPlainString());
                return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, false, reason);
            }
            return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, true, null);
        } catch (Exception e) {
            log.error(
                    "MaxInitialMarginEvaluator internal error for order {}: {}", request.orderId(), e.getMessage(), e);
            return new RuleResult(RiskRuleType.MAX_INITIAL_MARGIN, false, INTERNAL_ERROR_REASON);
        }
    }

    /**
     * 解析 policy params 的 maxInitialMarginRatio;null 走 {@link #DEFAULT_MAX_INITIAL_MARGIN_RATIO}(兜底 policy
     * 直接传 0.8,正常 policy validateParams 强制必填,理论不 null)。
     */
    private BigDecimal resolveRatio(RiskPolicy policy) {
        String ratioStr = policy.getParams().get(PARAM_KEY);
        if (ratioStr == null || ratioStr.isBlank()) {
            return DEFAULT_MAX_INITIAL_MARGIN_RATIO;
        }
        try {
            BigDecimal ratio = new BigDecimal(ratioStr);
            if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                log.warn(
                        "maxInitialMarginRatio {} out of (0,1], fallback to default {}",
                        ratio,
                        DEFAULT_MAX_INITIAL_MARGIN_RATIO);
                return DEFAULT_MAX_INITIAL_MARGIN_RATIO;
            }
            return ratio;
        } catch (NumberFormatException e) {
            log.warn(
                    "maxInitialMarginRatio '{}' not a valid decimal, fallback to default {}",
                    ratioStr,
                    DEFAULT_MAX_INITIAL_MARGIN_RATIO);
            return DEFAULT_MAX_INITIAL_MARGIN_RATIO;
        }
    }
}
