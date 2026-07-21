package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.BacktestOrderRejectedException;
import com.kwikquant.trading.domain.BacktestTaskNotRunningException;
import com.kwikquant.trading.domain.BacktestUnsupportedMarketTypeException;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.MarketSnapshot;
import com.kwikquant.trading.interfaces.BacktestOrderRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BacktestOrderService 单测:回测 single-order 撮合 + 虚拟账本(§3.1)。
 *
 * <p>账本 per-taskId 内存,initLedger/cleanupLedger 生命周期 = task RUNNING 生命周期(7303 用 ledger 存在性判定,
 * 避 trading→strategy 反查 BacktestTask)。
 *
 * <p>阶段2g:加 {@code submit_perp_throws7305} 覆盖 PERP 拒单(§11 M10-new,回测 PERP 留账阶段6+)。
 */
class BacktestOrderServiceTest {

    private final BacktestOrderService service = new BacktestOrderService();

    private static MarketSnapshot bar(String close, String low) {
        return new MarketSnapshot(
                Instant.parse("2024-01-15T08:00:00Z"),
                new BigDecimal(close),
                null,
                null,
                new BigDecimal(close),
                new BigDecimal(close),
                new BigDecimal(low),
                new BigDecimal(close),
                BigDecimal.TEN,
                List.of(),
                List.of());
    }

    private static BacktestOrderRequest buyMarket(String amount) {
        return new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal(amount),
                null,
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42150", "42050"),
                null,
                null,
                null);
    }

    @Test
    void submit_marketBuy_returnsFillAndDebitsCash() {
        service.initLedger(1L, new BigDecimal("100000"));
        Fill fill = service.submit(1L, buyMarket("0.1"));
        assertThat(fill).isNotNull();
        assertThat(fill.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(fill.getQty()).isEqualByComparingTo("0.1");
        assertThat(fill.getLiquidity()).isEqualTo("taker");
    }

    @Test
    void submit_limitNotCrossed_returnsNull() {
        service.initLedger(1L, new BigDecimal("100000"));
        BacktestOrderRequest buyLimit = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("40000"),
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42150", "42050"),
                null,
                null,
                null);
        // FAST: buy limit 触发条件 snap.low <= price; 42050 <= 40000 false → 未穿越
        Fill fill = service.submit(1L, buyLimit);
        assertThat(fill).isNull();
    }

    @Test
    void submit_limitCrossed_returnsFill() {
        service.initLedger(1L, new BigDecimal("100000"));
        BacktestOrderRequest buyLimit = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42100"),
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42150", "42050"),
                null,
                null,
                null);
        // 42050 <= 42100 true → 穿越, maker 成交
        Fill fill = service.submit(1L, buyLimit);
        assertThat(fill).isNotNull();
        assertThat(fill.getPrice()).isEqualByComparingTo("42100");
        assertThat(fill.getLiquidity()).isEqualTo("maker");
    }

    @Test
    void submit_cashInsufficient_throws7302() {
        service.initLedger(1L, new BigDecimal("100"));
        BacktestOrderRequest bigBuy = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("100"),
                null,
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42150", "42050"),
                null,
                null,
                null);
        assertThatThrownBy(() -> service.submit(1L, bigBuy)).isInstanceOf(BacktestOrderRejectedException.class);
    }

    @Test
    void submit_sellWithoutBase_throws7302() {
        service.initLedger(1L, new BigDecimal("100000"));
        BacktestOrderRequest sell = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42150", "42050"),
                null,
                null,
                null);
        assertThatThrownBy(() -> service.submit(1L, sell)).isInstanceOf(BacktestOrderRejectedException.class);
    }

    @Test
    void submit_taskNotRunning_throws7303() {
        assertThatThrownBy(() -> service.submit(99L, buyMarket("0.1")))
                .isInstanceOf(BacktestTaskNotRunningException.class);
    }

    @Test
    void cleanupLedger_thenSubmit_throws7303() {
        service.initLedger(1L, new BigDecimal("100000"));
        service.cleanupLedger(1L);
        assertThatThrownBy(() -> service.submit(1L, buyMarket("0.1")))
                .isInstanceOf(BacktestTaskNotRunningException.class);
    }

    @Test
    void submit_buyThenSell_worksAndRealizesPnl() {
        service.initLedger(1L, new BigDecimal("100000"));
        Fill buy = service.submit(1L, buyMarket("0.1"));
        assertThat(buy).isNotNull();
        BacktestOrderRequest sell = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                MarketType.SPOT,
                Exchange.BINANCE,
                bar("42500", "42400"),
                null,
                null,
                null);
        Fill sellFill = service.submit(1L, sell);
        assertThat(sellFill).isNotNull();
        assertThat(sellFill.getSide()).isEqualTo(OrderSide.SELL);
    }

    /**
     * 阶段2g(§11 M10-new):回测 PERP 留账阶段6+,BacktestOrderService 拒 PERP 单(返 7305)。
     * 请求语义校验优先于 task 运行态校验(无 ledger 也拒,400 BAD_REQUEST 优先于 409 CONFLICT)。
     */
    @Test
    void submit_perp_throws7305() {
        service.initLedger(1L, new BigDecimal("100000"));
        BacktestOrderRequest perpOpen = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                MarketType.PERP,
                Exchange.OKX,
                bar("42150", "42050"),
                PositionEffect.OPEN_LONG,
                10,
                MarginMode.ISOLATED);
        assertThatThrownBy(() -> service.submit(1L, perpOpen))
                .isInstanceOf(BacktestUnsupportedMarketTypeException.class);
    }

    /** PERP 拒单优先于 task not running(无 ledger 时 PERP 单仍返 7305 而非 7303)。 */
    @Test
    void submit_perpTaskNotRunning_stillThrows7305() {
        BacktestOrderRequest perpOpen = new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                MarketType.PERP,
                Exchange.OKX,
                bar("42150", "42050"),
                PositionEffect.OPEN_LONG,
                10,
                MarginMode.ISOLATED);
        assertThatThrownBy(() -> service.submit(99L, perpOpen))
                .isInstanceOf(BacktestUnsupportedMarketTypeException.class);
    }
}
