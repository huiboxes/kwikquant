package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.AccountSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultCcxtOrderAdapterTest {
    private DefaultCcxtOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DefaultCcxtOrderAdapter(mock(CcxtAuthExchangeFactory.class));
    }

    @Test
    void createOrder_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        Order order = new Order();
        order.setId(1L);
        order.setSymbol("BTC/USDT");
        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("not implemented");
    }

    @Test
    void cancelOrder_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        Order order = new Order();
        order.setId(1L);
        assertThatThrownBy(() -> adapter.cancelOrder(acct, order)).isInstanceOf(ExchangeException.class);
    }

    @Test
    void fetchSnapshot_returnsEmptySnapshot() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        AccountSnapshot snap = adapter.fetchSnapshot(acct);
        assertThat(snap).isNotNull();
        assertThat(snap.positions()).isEmpty();
        assertThat(snap.openOrders()).isEmpty();
    }
}
