package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 档位 C-3 BacktestLedger PERP 账本单测(包私有类,同包访问)。
 *
 * <p>覆盖 canApply PERP 宽松 / applyPerp 加仓 / CLOSE_SHORT PnL / CLOSE 无持仓 noop / getPerpPositions。
 * SPOT 路径由 BacktestOrderServiceTest 间接覆盖。
 */
class BacktestLedgerTest {

    @Test
    void canApply_perp_returnsTrue() {
        BacktestLedger ledger = new BacktestLedger(new BigDecimal("100"));
        assertThat(ledger.canApply(perpOrder(PositionEffect.OPEN_LONG, "0.1", "42000"), fill("42000", "0.1")))
                .isTrue(); // PERP 宽松(强平留账)
    }

    @Test
    void applyPerp_openLong_addToExistingPosition() {
        // 两次开多 → 加仓 avgEntry 加权,per-position 1 条
        BacktestLedger ledger = new BacktestLedger(new BigDecimal("100000"));
        ledger.apply(perpOrder(PositionEffect.OPEN_LONG, "0.1", "42000"), fill("42000", "0.1"));
        ledger.apply(perpOrder(PositionEffect.OPEN_LONG, "0.05", "43000"), fill("43000", "0.05"));
        assertThat(ledger.getPerpPositions()).hasSize(1);
        assertThat(ledger.getRealizedPnl()).isEqualByComparingTo("0"); // 未平仓无 PnL
    }

    @Test
    void applyPerp_closeShort_realizesPnl() {
        // OPEN_SHORT 42000 → CLOSE_SHORT 41000 → pnl=(41000-42000)×0.1×(-1 sideSign)=100
        BacktestLedger ledger = new BacktestLedger(new BigDecimal("100000"));
        ledger.apply(perpOrder(PositionEffect.OPEN_SHORT, "0.1", "42000"), fill("42000", "0.1"));
        ledger.apply(perpOrder(PositionEffect.CLOSE_SHORT, "0.1", "41000"), fill("41000", "0.1"));
        assertThat(ledger.getRealizedPnl()).isEqualByComparingTo("100");
        assertThat(ledger.getPerpPositions()).isEmpty(); // 全平清掉
    }

    @Test
    void applyPerp_closeNoPosition_noop() {
        // 无持仓 CLOSE → noop 不抛
        BacktestLedger ledger = new BacktestLedger(new BigDecimal("100000"));
        ledger.apply(perpOrder(PositionEffect.CLOSE_LONG, "0.1", "42000"), fill("42000", "0.1"));
        assertThat(ledger.getRealizedPnl()).isEqualByComparingTo("0");
        assertThat(ledger.getPerpPositions()).isEmpty();
    }

    @Test
    void applyPerp_openThenPartialClose_keepsRemainingPosition() {
        // OPEN 0.1 → CLOSE 0.05 部分平仓,剩余 0.05
        BacktestLedger ledger = new BacktestLedger(new BigDecimal("100000"));
        ledger.apply(perpOrder(PositionEffect.OPEN_LONG, "0.1", "42000"), fill("42000", "0.1"));
        ledger.apply(perpOrder(PositionEffect.CLOSE_LONG, "0.05", "43000"), fill("43000", "0.05"));
        // pnl=(43000-42000)×0.05×1=50
        assertThat(ledger.getRealizedPnl()).isEqualByComparingTo("50");
        assertThat(ledger.getPerpPositions()).hasSize(1); // 剩余未清
    }

    private static Order perpOrder(PositionEffect effect, String amount, String price) {
        OrderSide side = effect == PositionEffect.OPEN_LONG || effect == PositionEffect.CLOSE_LONG
                ? OrderSide.BUY
                : OrderSide.SELL;
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                side,
                OrderType.MARKET,
                new BigDecimal(amount),
                price != null ? new BigDecimal(price) : null,
                null,
                TimeInForce.GTC,
                null,
                null,
                10,
                MarginMode.ISOLATED,
                effect);
        return Order.create(cmd, pseudoPair("BTC/USDT"));
    }

    private static Fill fill(String price, String qty) {
        return Fill.create(
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                new BigDecimal(price),
                new BigDecimal(qty),
                BigDecimal.ZERO,
                "USDT",
                "taker",
                "fill-1",
                Instant.now());
    }

    private static TradingPairInfo pseudoPair(String symbol) {
        String base = symbol.substring(0, symbol.indexOf('/'));
        String quote = symbol.substring(symbol.indexOf('/') + 1);
        return new TradingPairInfo(
                Exchange.OKX,
                MarketType.PERP,
                symbol,
                base,
                quote,
                new BigDecimal("0.0000001"),
                new BigDecimal("1000000"),
                null,
                null,
                true);
    }
}
