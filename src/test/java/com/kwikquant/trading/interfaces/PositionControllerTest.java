package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link PositionController#close(long)}.
 *
 * <p>Pure Mockito style (consistent with {@link OrderControllerTest}). 验证平仓按 marginMode 分流:
 * PERP 持仓走 {@link OrderSubmitCommand#perp} 工厂派生 CLOSE_LONG/CLOSE_SHORT + 透传
 * leverage/marginMode(回归:原 close 对所有持仓都用 spot 工厂,PERP 仓平不掉、保证金不释放);
 * SPOT 持仓走 spot 反向市价单。flat / 不存在的持仓返 404。
 */
class PositionControllerTest {

    private PositionService positionService;
    private ExchangeAccountService accountService;
    private MarketDataService marketDataService;
    private TradingService tradingService;
    private PositionController controller;

    @BeforeEach
    void setUp() {
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        marketDataService = mock(MarketDataService.class);
        tradingService = mock(TradingService.class);
        com.kwikquant.trading.infrastructure.FundingSettlementMapper fundingSettlementMapper =
                mock(com.kwikquant.trading.infrastructure.FundingSettlementMapper.class);
        controller = new PositionController(
                positionService, accountService, marketDataService, tradingService, fundingSettlementMapper);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void close_perpLongPosition_usesPerpFactoryWithCloseLongEffect() {
        Position pos = openPerp(Position.SIDE_LONG, "LONG", 10);
        when(positionService.findById(128L)).thenReturn(pos);
        when(accountService.getOwned(eq(1L), eq(42L))).thenReturn(mock(ExchangeAccount.class));

        controller.close(128L);

        OrderSubmitCommand cmd = captureSubmitted();
        assertThat(cmd.marketType()).isEqualTo(MarketType.PERP);
        assertThat(cmd.side()).isEqualTo(OrderSide.SELL);
        assertThat(cmd.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(cmd.amount()).isEqualByComparingTo("0.5");
        assertThat(cmd.leverage()).isEqualTo(10);
        assertThat(cmd.marginMode()).isEqualTo(MarginMode.ISOLATED);
        assertThat(cmd.positionEffect()).isEqualTo(PositionEffect.CLOSE_LONG);
    }

    @Test
    void close_perpShortPosition_derivesCloseShortAndBuySide() {
        Position pos = openPerp(Position.SIDE_SHORT, "SHORT", 20);
        when(positionService.findById(131L)).thenReturn(pos);
        when(accountService.getOwned(eq(1L), eq(42L))).thenReturn(mock(ExchangeAccount.class));

        controller.close(131L);

        OrderSubmitCommand cmd = captureSubmitted();
        assertThat(cmd.marketType()).isEqualTo(MarketType.PERP);
        assertThat(cmd.side()).isEqualTo(OrderSide.BUY);
        assertThat(cmd.positionEffect()).isEqualTo(PositionEffect.CLOSE_SHORT);
        assertThat(cmd.leverage()).isEqualTo(20);
    }

    @Test
    void close_spotPosition_usesSpotFactoryNoPerpFields() {
        Position pos = Position.flat(1L, "BTC/USDT");
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(new BigDecimal("0.25"));
        when(positionService.findById(128L)).thenReturn(pos);
        when(accountService.getOwned(eq(1L), eq(42L))).thenReturn(mock(ExchangeAccount.class));

        controller.close(128L);

        OrderSubmitCommand cmd = captureSubmitted();
        assertThat(cmd.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(cmd.side()).isEqualTo(OrderSide.SELL);
        assertThat(cmd.leverage()).isNull();
        assertThat(cmd.marginMode()).isNull();
        assertThat(cmd.positionEffect()).isNull();
    }

    @Test
    void close_flatPosition_throwsNotFound() {
        when(positionService.findById(200L)).thenReturn(Position.flat(1L, "BTC/USDT"));
        assertThatThrownBy(() -> controller.close(200L)).isInstanceOf(ResourceNotFoundException.class);
        verify(tradingService, never()).submit(any());
    }

    @Test
    void close_missingPosition_throwsNotFound() {
        when(positionService.findById(999L)).thenReturn(null);
        assertThatThrownBy(() -> controller.close(999L)).isInstanceOf(ResourceNotFoundException.class);
        verify(tradingService, never()).submit(any());
    }

    /** 构造已开仓 PERP 持仓(qty=0.5,leverage,ISOLATED)。 */
    private static Position openPerp(String side, String positionSide, int leverage) {
        Position p = Position.flat(1L, "BTC/USDT");
        p.setSide(side);
        p.setPositionSide(positionSide);
        p.setQty(new BigDecimal("0.5"));
        p.setLeverage(leverage);
        p.setMarginMode(MarginMode.ISOLATED);
        return p;
    }

    private OrderSubmitCommand captureSubmitted() {
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        return captor.getValue();
    }
}
