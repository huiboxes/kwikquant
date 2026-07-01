package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class OrderRouterTest {

    @Test
    void routesPaperToPaperExecutor() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        PaperExecutor paper = mock(PaperExecutor.class);
        when(ctx.getBean(PaperExecutor.class)).thenReturn(paper);
        OrderRouter router = new OrderRouter(ctx);

        ExchangeAccount account = new ExchangeAccount();
        account.setPaperTrading(true);

        assertThat(router.route(account)).isSameAs(paper);
        verify(ctx).getBean(PaperExecutor.class);
    }

    @Test
    void routesLiveToLiveExecutor() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        LiveExecutor live = mock(LiveExecutor.class);
        when(ctx.getBean(LiveExecutor.class)).thenReturn(live);
        OrderRouter router = new OrderRouter(ctx);

        ExchangeAccount account = new ExchangeAccount();
        account.setPaperTrading(false);

        assertThat(router.route(account)).isSameAs(live);
        verify(ctx).getBean(LiveExecutor.class);
    }
}
