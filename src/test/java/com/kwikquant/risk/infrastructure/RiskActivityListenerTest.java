package com.kwikquant.risk.infrastructure;

import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class RiskActivityListenerTest {

    @Test
    void onRiskTriggered_anyEvent_shouldPublishActivityCreatedEvent() {
        var publisher = mock(ApplicationEventPublisher.class);
        var listener = new RiskActivityListener(publisher);

        var event =
                new RiskTriggeredEvent(42L, new OrderId(9006L), new AccountId(1L), null, "MAX_NOTIONAL", Instant.now());

        listener.onRiskTriggered(event);

        verify(publisher).publishEvent(argThat((Object e) -> {
            if (!(e instanceof ActivityCreatedEvent a)) return false;
            return "RISK_TRIGGERED".equals(a.type())
                    && a.title().contains("o-9006")
                    && a.subtitle().contains("MAX_NOTIONAL");
        }));
    }
}
