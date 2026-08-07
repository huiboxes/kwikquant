package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionEnrichment;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Position;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link PositionController}.
 *
 * <p>close 平仓逻辑下沉到 {@link TradingService#closePosition(long)}(REST 与 MCP 共用 DRY),
 * 此处只验委托;list 验 toDto 富化委托 {@link PositionEnricher}(与 MCP PositionView 共用)。
 * 分流(PERP CLOSE_LONG/CLOSE_SHORT + 透传 leverage/marginMode / SPOT 反向单)由 TradingService
 * 单测覆盖。
 */
class PositionControllerTest {

    private PositionService positionService;
    private ExchangeAccountService accountService;
    private PositionEnricher positionEnricher;
    private TradingService tradingService;
    private PositionController controller;

    @BeforeEach
    void setUp() {
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        positionEnricher = mock(PositionEnricher.class);
        tradingService = mock(TradingService.class);
        controller = new PositionController(positionService, accountService, positionEnricher, tradingService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void close_shouldDelegateToTradingServiceClosePosition() {
        when(tradingService.closePosition(128L))
                .thenReturn(new OrderSubmitResult(200L, OrderStatus.FILLED, 1L, Instant.now()));

        var result = controller.close(128L);

        assertThat(result.data().orderId()).isEqualTo(200L);
        verify(tradingService).closePosition(128L);
    }

    @Test
    void list_shouldEnrichPositionsWithMarketAndFunding() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(7L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(7L, 42L)).thenReturn(acct);
        Position pos = Position.flat(7L, "BTC/USDT");
        pos.setId(1L);
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(new BigDecimal("0.5"));
        pos.setLeverage(10);
        pos.setMarginMode(MarginMode.ISOLATED);
        when(positionService.findByAccount(7L)).thenReturn(List.of(pos));
        when(positionEnricher.enrich(pos, Exchange.BINANCE))
                .thenReturn(
                        new PositionEnrichment(new BigDecimal("50000"), new BigDecimal("120"), new BigDecimal("3.2")));

        var result = controller.list(7L, null, mock(HttpServletRequest.class));

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).symbol()).isEqualTo("BTC/USDT");
        assertThat(result.data().get(0).leverage()).isEqualTo(10);
        assertThat(result.data().get(0).marginMode()).isEqualTo("ISOLATED");
        assertThat(result.data().get(0).currentPrice()).isEqualByComparingTo("50000");
        assertThat(result.data().get(0).unrealizedPnl()).isEqualByComparingTo("120");
        assertThat(result.data().get(0).cumulativeFunding()).isEqualByComparingTo("3.2");
        verify(positionEnricher).enrich(pos, Exchange.BINANCE);
    }
}
