package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.evaluators.MaxNotionalEvaluator;
import com.kwikquant.risk.domain.evaluators.OrderFrequencyEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers domain-layer {@link RuleEvaluator} implementations as Spring beans.
 *
 * <p>Evaluators live in {@code risk.domain.evaluators} (no Spring annotations on them)
 * to satisfy the ArchUnit rule that domain packages must not depend on Spring.
 *
 * <p>v1 implements MAX_NOTIONAL and ORDER_FREQUENCY. DAILY_LOSS_LIMIT is intentionally
 * NOT registered: it requires a daily P&amp;L aggregate (unrealized + realized) that depends
 * on a PnL service planned for a later wave. Until then, RiskPolicyManagementService.create
 * rejects DAILY_LOSS_LIMIT (absent from supportedTypes), preventing stub rules that silently
 * pass and give users a false sense of safety. The DailyLossLimitEvaluator class is retained
 * for future activation.
 */
@Configuration
class RiskConfig {

    @Bean
    RuleEvaluator maxNotionalEvaluator() {
        return new MaxNotionalEvaluator();
    }

    @Bean
    RuleEvaluator orderFrequencyEvaluator() {
        return new OrderFrequencyEvaluator();
    }
}
