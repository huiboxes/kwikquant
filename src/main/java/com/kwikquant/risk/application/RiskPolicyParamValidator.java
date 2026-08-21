package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.evaluators.DailyLossLimitEvaluator;
import com.kwikquant.risk.domain.evaluators.MaxInitialMarginEvaluator;
import com.kwikquant.risk.domain.evaluators.MaxNotionalEvaluator;
import com.kwikquant.risk.domain.evaluators.OrderFrequencyEvaluator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 风控策略参数校验 —— 规则参数合法性的唯一真相源。
 *
 * <p>写路径({@link RiskPolicyManagementService} 的 create/update/applyBulk)与 AI 自然语言解析预览
 * ({@code ai} 模块 {@code RiskPolicyParseService})共用同一套校验,避免"预览时合法、落库时被拒"
 * 的口径漂移。校验失败统一抛 {@link IllegalArgumentException}(全局异常处理器映射 400/3001)。
 *
 * <p>纯函数工具类,无 Spring 依赖。未知 extra key 仅 warn 不拒(历史兼容)。
 */
public final class RiskPolicyParamValidator {

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyParamValidator.class);

    private static final int MAX_PARAM_KEYS = 10;
    private static final BigDecimal MAX_NOTIONAL_AMOUNT = new BigDecimal("10000000");
    private static final BigDecimal MAX_LOSS_AMOUNT = new BigDecimal("10000000");
    private static final int MAX_FREQUENCY = 1000;

    private RiskPolicyParamValidator() {}

    /**
     * Validates rule-specific params. Rejects missing required keys and out-of-range values. Unknown extra keys
     * are logged as warnings but not rejected.
     */
    public static void validate(RiskRuleType ruleType, Map<String, String> params) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (params.size() > MAX_PARAM_KEYS) {
            throw new IllegalArgumentException("params map exceeds maximum of " + MAX_PARAM_KEYS + " keys");
        }

        switch (ruleType) {
            case MAX_NOTIONAL -> validateMaxNotionalParams(params);
            case DAILY_LOSS_LIMIT -> validateDailyLossLimitParams(params);
            case ORDER_FREQUENCY -> validateOrderFrequencyParams(params);
            case MAX_INITIAL_MARGIN -> validateMaxInitialMarginParams(params);
        }
    }

    private static void validateMaxNotionalParams(Map<String, String> params) {
        String key = MaxNotionalEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for MAX_NOTIONAL rule");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid decimal: " + value);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (amount.compareTo(MAX_NOTIONAL_AMOUNT) > 0) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_NOTIONAL_AMOUNT.toPlainString());
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private static void validateDailyLossLimitParams(Map<String, String> params) {
        String key = DailyLossLimitEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for DAILY_LOSS_LIMIT rule");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid decimal: " + value);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (amount.compareTo(MAX_LOSS_AMOUNT) > 0) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_LOSS_AMOUNT.toPlainString());
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private static void validateOrderFrequencyParams(Map<String, String> params) {
        String key = OrderFrequencyEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for ORDER_FREQUENCY rule");
        }
        int maxPerMinute;
        try {
            maxPerMinute = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid integer: " + value);
        }
        if (maxPerMinute <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (maxPerMinute > MAX_FREQUENCY) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_FREQUENCY);
        }
        warnUnknownKeys(params, Set.of(key));
    }

    /** MAX_INITIAL_MARGIN ratio 必填,范围 (0, 1](0.8=80% 留 20% 缓冲)。 */
    private static void validateMaxInitialMarginParams(Map<String, String> params) {
        String key = MaxInitialMarginEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for MAX_INITIAL_MARGIN rule");
        }
        BigDecimal ratio;
        try {
            ratio = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid decimal: " + value);
        }
        if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(key + " must be in (0, 1], got: " + ratio.toPlainString());
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private static void warnUnknownKeys(Map<String, String> params, Set<String> known) {
        for (String key : params.keySet()) {
            if (!known.contains(key)) {
                log.warn("Unknown param key '{}' for risk policy, ignored", key);
            }
        }
    }
}
