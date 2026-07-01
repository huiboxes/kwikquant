package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.evaluators.DailyLossLimitEvaluator;
import com.kwikquant.risk.domain.evaluators.MaxNotionalEvaluator;
import com.kwikquant.risk.domain.evaluators.OrderFrequencyEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers domain-layer {@link RuleEvaluator} implementations as Spring beans.
 *
 * <p>Evaluators live in {@code risk.domain.evaluators} (no Spring annotations on them)
 * to satisfy the ArchUnit rule that domain packages must not depend on Spring.
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

    @Bean
    RuleEvaluator dailyLossLimitEvaluator() {
        return new DailyLossLimitEvaluator();
    }
}
