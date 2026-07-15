package com.kwikquant.strategy.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.*;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class StrategyActivityListenerTest {

    @Test
    void onStrategyStatusChanged_anyEvent_shouldPublishActivityCreatedEvent() {
        var publisher = mock(ApplicationEventPublisher.class);
        var mapper = mock(StrategyMapper.class);
        var listener = new StrategyActivityListener(publisher, mapper);

        StrategyDefinition s = StrategyDefinition.create(42L, "BTC Grid", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(10L);
        when(mapper.findById(10L)).thenReturn(s);

        var event = new StrategyStatusChangedEvent(
                42L,
                new StrategyId(10L),
                StrategyStatus.READY,
                StrategyStatus.RUNNING,
                Instant.now());

        listener.onStrategyStatusChanged(event);

        verify(publisher).publishEvent(argThat((Object e) -> {
            if (!(e instanceof ActivityCreatedEvent a)) return false;
            return "STRATEGY_STATE_CHANGED".equals(a.type())
                    && a.title().contains("BTC Grid")
                    && a.title().contains("RUNNING");
        }));
    }
}
