package com.kwikquant.trading.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingBootstrapTest {
    private ExchangeAccountService accountService;
    private PaperExecutor paperExecutor;
    private LiveExecutor liveExecutor;
    private TradingBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        paperExecutor = mock(PaperExecutor.class);
        liveExecutor = mock(LiveExecutor.class);
        bootstrap = new TradingBootstrap(accountService, paperExecutor, liveExecutor);
    }

    @Test
    void bootstrapAllAccounts_routesPaperAndLiveCorrectly() {
        ExchangeAccount paper = account(1L, true);
        ExchangeAccount live = account(2L, false);
        when(accountService.findAll()).thenReturn(List.of(paper, live));
        bootstrap.bootstrapAllAccounts();
        verify(paperExecutor).bootstrapActivePaperOrders(1L);
        verify(liveExecutor).startupSnapshot(live);
    }

    @Test
    void bootstrapAllAccounts_whenNoAccounts_noOp() {
        when(accountService.findAll()).thenReturn(List.of());
        bootstrap.bootstrapAllAccounts();
        verifyNoInteractions(paperExecutor);
        verifyNoInteractions(liveExecutor);
    }

    @Test
    void bootstrapAllAccounts_whenFindAllFails_returnsEarly() {
        when(accountService.findAll()).thenThrow(new RuntimeException("DB down"));
        bootstrap.bootstrapAllAccounts();
        verifyNoInteractions(paperExecutor);
        verifyNoInteractions(liveExecutor);
    }

    @Test
    void bootstrapAllAccounts_whenOneAccountFails_continuesOthers() {
        ExchangeAccount fail = account(1L, true);
        ExchangeAccount ok = account(2L, true);
        when(accountService.findAll()).thenReturn(List.of(fail, ok));
        doThrow(new RuntimeException("boom")).when(paperExecutor).bootstrapActivePaperOrders(1L);
        bootstrap.bootstrapAllAccounts();
        verify(paperExecutor).bootstrapActivePaperOrders(2L);
    }

    @Test
    void bootstrapAllAccounts_multipleLiveAccounts_allProcessed() {
        ExchangeAccount l1 = account(1L, false);
        ExchangeAccount l2 = account(2L, false);
        when(accountService.findAll()).thenReturn(List.of(l1, l2));
        bootstrap.bootstrapAllAccounts();
        verify(liveExecutor).startupSnapshot(l1);
        verify(liveExecutor).startupSnapshot(l2);
        verifyNoInteractions(paperExecutor);
    }

    private ExchangeAccount account(long id, boolean paper) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setExchange(Exchange.BINANCE);
        a.setPaperTrading(paper);
        a.setStatus("ACTIVE");
        return a;
    }
}
