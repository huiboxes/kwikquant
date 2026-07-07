package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kwikquant.trading.application.TradingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PaperAccountControllerTest {

    private TradingService tradingService;
    private PaperAccountController controller;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        controller = new PaperAccountController(tradingService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reset_delegatesToServiceWithCurrentUser() {
        var result = controller.reset(7L);

        verify(tradingService).resetPaperAccount(7L, 42L);
        assertThat(result.code()).isZero();
        assertThat(result.data().accountId()).isEqualTo(7L);
        assertThat(result.data().action()).isEqualTo("reset");
    }
}
