package com.kwikquant.report.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.report.application.PortfolioService.AccountSummary;
import com.kwikquant.report.application.PortfolioService.PortfolioPnl;
import com.kwikquant.report.application.PortfolioService.PortfolioSummary;
import com.kwikquant.report.application.PortfolioService.PositionPnl;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.Exchange;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PortfolioControllerTest {

    private PortfolioService portfolioService;
    private PortfolioController controller;

    @BeforeEach
    void setUp() {
        portfolioService = mock(PortfolioService.class);
        controller = new PortfolioController(portfolioService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getSummary_returnsPortfolioSummary() {
        PortfolioSummary summary = new PortfolioSummary(
                List.of(new AccountSummary(1L, Exchange.BINANCE, "main", List.of(), new BigDecimal("10000"))),
                new BigDecimal("10000"));
        when(portfolioService.getSummary(42L, null)).thenReturn(summary);

        ApiResponse<PortfolioSummary> response = controller.summary(null);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().totalUsdt()).isEqualByComparingTo("10000");
        assertThat(response.data().accounts()).hasSize(1);
        verify(portfolioService).getSummary(42L, null);
    }

    @Test
    void getPnl_returnsPortfolioPnl() {
        PortfolioPnl pnl = new PortfolioPnl(
                List.of(new PositionPnl(
                        1L,
                        "BTC/USDT",
                        "long",
                        new BigDecimal("0.1"),
                        new BigDecimal("50000"),
                        new BigDecimal("55000"),
                        new BigDecimal("500"),
                        new BigDecimal("200"))),
                new BigDecimal("500"));
        when(portfolioService.getPnl(42L, null)).thenReturn(pnl);

        ApiResponse<PortfolioPnl> response = controller.pnl(null);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().totalUnrealizedPnl()).isEqualByComparingTo("500");
        assertThat(response.data().positions()).hasSize(1);
        assertThat(response.data().positions().getFirst().symbol()).isEqualTo("BTC/USDT");
        verify(portfolioService).getPnl(42L, null);
    }
}
