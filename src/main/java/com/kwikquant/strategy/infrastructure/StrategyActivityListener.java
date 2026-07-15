package com.kwikquant.strategy.infrastructure;

import com.kwikquant.shared.types.ActivityCreatedEvent;
import com.kwikquant.shared.types.ActivityTypes;
import com.kwikquant.shared.types.StrategyStatusChangedEvent;
import com.kwikquant.strategy.domain.StrategyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StrategyActivityListener {

    private static final Logger log = LoggerFactory.getLogger(StrategyActivityListener.class);
    private final ApplicationEventPublisher publisher;
    private final StrategyMapper strategyMapper;

    public StrategyActivityListener(ApplicationEventPublisher publisher, StrategyMapper strategyMapper) {
        this.publisher = publisher;
        this.strategyMapper = strategyMapper;
    }

    @EventListener
    public void onStrategyStatusChanged(StrategyStatusChangedEvent event) {
        try {
            StrategyDefinition s = strategyMapper.findById(event.strategyId().value());
            String name = s != null ? s.getName() : "策略#" + event.strategyId().value();
            String title = name + " " + event.newStatus();

            publisher.publishEvent(new ActivityCreatedEvent(
                    event.userId(),
                    ActivityTypes.STRATEGY_STATE_CHANGED,
                    title,
                    null,
                    event.timestamp()));
        } catch (Exception e) {
            log.debug("[activity] failed to convert strategy event strategyId={}: {}", event.strategyId().value(), e.getMessage());
        }
    }
}
