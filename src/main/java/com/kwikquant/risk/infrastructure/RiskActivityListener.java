package com.kwikquant.risk.infrastructure;

import com.kwikquant.shared.types.ActivityCreatedEvent;
import com.kwikquant.shared.types.ActivityTypes;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RiskActivityListener {

    private static final Logger log = LoggerFactory.getLogger(RiskActivityListener.class);
    private final ApplicationEventPublisher publisher;

    public RiskActivityListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onRiskTriggered(RiskTriggeredEvent event) {
        try {
            String title = "风控拦截 o-" + event.orderId().value();
            String subtitle = "触发 " + event.reason();

            publisher.publishEvent(new ActivityCreatedEvent(
                    event.userId(),
                    ActivityTypes.RISK_TRIGGERED,
                    title,
                    subtitle,
                    event.timestamp()));
        } catch (Exception e) {
            log.debug("[activity] failed to convert risk event orderId={}: {}", event.orderId().value(), e.getMessage());
        }
    }
}
