package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import org.junit.jupiter.api.Test;

class OrderRouterTest {

    @Test
    void routesPaperToPaperExecutor() {
        PaperExecutor paper = mock(PaperExecutor.class);
        LiveExecutor live = mock(LiveExecutor.class);
        OrderRouter router = new OrderRouter(paper, live);

        ExchangeAccount account = new ExchangeAccount();
        account.setPaperTrading(true);

        assertThat(router.route(account)).isSameAs(paper);
    }

    @Test
    void routesLiveToLiveExecutor() {
        PaperExecutor paper = mock(PaperExecutor.class);
        LiveExecutor live = mock(LiveExecutor.class);
        OrderRouter router = new OrderRouter(paper, live);

        ExchangeAccount account = new ExchangeAccount();
        account.setPaperTrading(false);

        assertThat(router.route(account)).isSameAs(live);
    }
}
