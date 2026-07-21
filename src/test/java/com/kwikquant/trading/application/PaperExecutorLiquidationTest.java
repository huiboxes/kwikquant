package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 阶段2f:PaperExecutor.onTicker 强平判定 + markPrice 缓存单元测试。
 *
 * <p>覆盖 spec §3.3 + §12 B1-s/m3-s:
 * <ul>
 *   <li>{@link PaperExecutor#computeMarkPrice} mid=(bid+ask)/2 fallback last(§12 m3-s)</li>
 *   <li>{@link PaperExecutor#onTicker} 开头强平判定:多头 markPrice &lt;= liq / 空头 markPrice &gt;= liq
 *       → 调 {@link ExecutionService#processLiquidation}(§3.3)</li>
 *   <li>强平不触发(markPrice 安全)不调 processLiquidation</li>
 *   <li>processLiquidation 抛异常 → catch,不阻断后续撮合(强平幂等,下 tick 再判)</li>
 * </ul>
 */
class PaperExecutorLiquidationTest {

    private MarketDataService marketDataService;
    private OrderMapper orderMapper;
    private ExecutionService executionService;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private PositionService positionService;
    private PaperExecutor executor;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        orderMapper = mock(OrderMapper.class);
        executionService = mock(ExecutionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        positionService = mock(PositionService.class);
        executor = new PaperExecutor(
                marketDataService, orderMapper, executionService, wsBroadcaster, accountService, positionService);
    }

    // ---------- markPrice 计算(§12 m3-s)----------

    @Test
    void computeMarkPrice_bidAskAvailable_returnsMid() {
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));
        // mid = (36900 + 37100) / 2 = 37000
        assertThat(PaperExecutor.computeMarkPrice(t)).isEqualByComparingTo("37000");
    }

    @Test
    void computeMarkPrice_bidAskNull_fallsBackToLast() {
        Ticker t = ticker(Exchange.OKX, null, null, new BigDecimal("38000"));
        assertThat(PaperExecutor.computeMarkPrice(t)).isEqualByComparingTo("38000");
    }

    @Test
    void computeMarkPrice_bidZero_fallsBackToLast() {
        // bid=0(异常数据)不满足 signum>0,fallback last
        Ticker t = ticker(Exchange.OKX, BigDecimal.ZERO, new BigDecimal("37100"), new BigDecimal("37000"));
        assertThat(PaperExecutor.computeMarkPrice(t)).isEqualByComparingTo("37000");
    }

    // ---------- 强平判定 ----------

    @Test
    void onTicker_longPositionMarkPriceBelowLiq_triggersLiquidation() {
        // LONG qty=0.1, liq=37800, markPrice=37000(mid) <= liq → 强平
        Position pos = position(100L, "LONG", new BigDecimal("0.1"), new BigDecimal("37800"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

        // markPrice=37000(mid),positionId=100,triggerOrderId=null(BigDecimal eq scale 陷阱用 argThat)
        verify(executionService)
                .processLiquidation(
                        eq(100L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("37000")) == 0), isNull());
    }

    @Test
    void onTicker_shortPositionMarkPriceAboveLiq_triggersLiquidation() {
        // SHORT qty=0.1, liq=46200, markPrice=47000(mid) >= liq → 强平
        Position pos = position(200L, "SHORT", new BigDecimal("0.1"), new BigDecimal("46200"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.BINANCE))
                .thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.BINANCE, new BigDecimal("46900"), new BigDecimal("47100"), new BigDecimal("47000"));

        executor.onTicker(t);

        verify(executionService)
                .processLiquidation(
                        eq(200L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("47000")) == 0), isNull());
    }

    @Test
    void onTicker_markPriceSafe_noLiquidation() {
        // LONG liq=37800, markPrice=38000(mid) > liq → 不强平
        Position pos = position(300L, "LONG", new BigDecimal("0.1"), new BigDecimal("37800"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("37900"), new BigDecimal("38100"), new BigDecimal("38000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_shortMarkPriceSafe_noLiquidation() {
        // SHORT liq=46200, markPrice=46000(mid) < liq → 不强平(空头未涨破)
        Position pos = position(400L, "SHORT", new BigDecimal("0.1"), new BigDecimal("46200"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("45900"), new BigDecimal("46100"), new BigDecimal("46000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_flatPositionSkipped_noLiquidation() {
        // qty=0(flat)不判强平
        Position pos = position(500L, "LONG", BigDecimal.ZERO, new BigDecimal("37800"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_multiplePositions_onlyTriggeredOneLiquidated() {
        // 两仓:LONG(liq=37800,触发) + SHORT(liq=46200,markPrice=37000 < liq 不触发)
        Position longPos = position(600L, "LONG", new BigDecimal("0.1"), new BigDecimal("37800"));
        Position shortPos = position(601L, "SHORT", new BigDecimal("0.1"), new BigDecimal("46200"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(longPos, shortPos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

        // 只强平 LONG 仓
        verify(executionService)
                .processLiquidation(
                        eq(600L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("37000")) == 0), isNull());
        verify(executionService, never()).processLiquidation(eq(601L), any(), any());
    }

    @Test
    void onTicker_liquidationThrows_continuesSilently() {
        Position pos = position(700L, "LONG", new BigDecimal("0.1"), new BigDecimal("37800"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        doThrow(new com.kwikquant.trading.infrastructure.ConcurrencyConflictException("CAS failed"))
                .when(executionService)
                .processLiquidation(eq(700L), any(), any());
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        // 不抛(强平失败 catch,下 tick 再判)
        executor.onTicker(t);

        verify(executionService).processLiquidation(eq(700L), any(), any());
    }

    @Test
    void onTicker_markPriceNull_skipsLiquidationCheck() {
        // bid/ask/last 全 null → markPrice=null,不查持仓不强平
        Position pos = position(800L, "LONG", new BigDecimal("0.1"), new BigDecimal("37800"));
        when(positionService.findPerpForLiquidation(any(), any())).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, null, null, null);

        executor.onTicker(t);

        // markPrice null 时连 findPerpForLiquidation 都不调(短路)
        verify(positionService, never()).findPerpForLiquidation(any(), any());
        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    // ---------- helpers ----------

    private static Ticker ticker(Exchange exchange, BigDecimal bid, BigDecimal ask, BigDecimal last) {
        return new Ticker(
                exchange,
                MarketType.PERP,
                "BTC/USDT",
                last,
                bid,
                ask,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static Position position(long id, String positionSide, BigDecimal qty, BigDecimal liqPrice) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(1L);
        p.setSymbol("BTC/USDT");
        p.setSide("LONG".equals(positionSide) ? Position.SIDE_LONG : Position.SIDE_SHORT);
        p.setPositionSide(positionSide);
        p.setQty(qty);
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setLiquidationPrice(liqPrice);
        p.setLeverage(10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setFrozenAmount(new BigDecimal("420"));
        p.setVersion(1L);
        return p;
    }
}
