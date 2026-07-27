package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.InsufficientBalanceException;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.RejectFillException;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * PositionService 单元测试(Mockito mock PositionMapper)。聚焦 JaCoCo 预存债未覆盖方法:
 *
 * <ul>
 *   <li>{@code recomputeAllLiquidationPrices} — 全量重算(CAS 成功 / CAS 冲突跳过 / 空列表 / null mmr)
 *   <li>{@code requireBalance} — 现货粗略保证金校验三分支(null / 不足 / 充足)
 *   <li>{@code findById} / {@code findPerpForLiquidation} — Mapper 委托
 *   <li>{@code applyFill} 10 参重载 PERP 路径(flat 插入 / 已存在 casUpdate / CLOSE on flat 抛
 *       RejectFillException / CAS 重试耗尽 / insert DuplicateKey 重试)。
 *       该路径顺带覆盖 private {@code derivePositionSide} 的四向分支。
 * </ul>
 *
 * <p>纯逻辑分支(applyDelta / applyPerpDelta)已有 PositionServiceDeltaTest /
 * PositionServicePerpDeltaTest 专项覆盖;SPOT applyFill 走 PositionServiceIntegrationTest。
 */
class PositionServiceTest {

    private PositionMapper positionMapper;
    private PositionService positionService;

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** 构造已开仓的 LONG PERP 持仓(qty=0.05,lev=10,ISOLATED)。 */
    private static Position existingLongPerp() {
        Position p = Position.flat(1L, "BTC/USDT");
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("LONG");
        p.setQty(bd("0.05"));
        p.setAvgEntryPrice(bd("42000"));
        p.setFrozenAmount(bd("210"));
        p.setLeverage(10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setLiquidationPrice(p.computeLiquidationPrice(bd("0.005")));
        p.setVersion(3L);
        return p;
    }

    @BeforeEach
    void setUp() {
        positionMapper = mock(PositionMapper.class);
        positionService = new PositionService(positionMapper);
    }

    // ---------------- recomputeAllLiquidationPrices ----------------

    @Test
    void recomputeAllLiquidationPrices_emptyList_skipsCasUpdate() {
        when(positionMapper.findAllPerpPositions()).thenReturn(List.of());

        positionService.recomputeAllLiquidationPrices(bd("0.01"));

        verify(positionMapper, never()).casUpdate(any());
    }

    @Test
    void recomputeAllLiquidationPrices_casSuccessUpdatesVersionAndConflictSkips() {
        // 第一个 CAS 成功(affected=1) → version+1;第二个 CAS 冲突(affected=0) → 跳过
        Position ok = existingLongPerp();
        Position conflict = existingLongPerp();
        when(positionMapper.findAllPerpPositions()).thenReturn(List.of(ok, conflict));
        when(positionMapper.casUpdate(ok)).thenReturn(1);
        when(positionMapper.casUpdate(conflict)).thenReturn(0);

        // null maintMarginRate → 走 Position.computeLiquidationPrice 默认 0.005 分支
        positionService.recomputeAllLiquidationPrices(null);

        // 第一个成功:version 自增 4
        assertThat(ok.getVersion()).isEqualTo(4L);
        // 第二个冲突:version 不变
        assertThat(conflict.getVersion()).isEqualTo(3L);
        verify(positionMapper).casUpdate(ok);
        verify(positionMapper).casUpdate(conflict);
        // 强平价被 setLiquidationPrice 重写
        assertThat(ok.getLiquidationPrice()).isNotNull();
    }

    // ---------------- requireBalance ----------------

    @Test
    void requireBalance_nullAvailable_throwsInsufficientBalance() {
        assertThatThrownBy(() -> positionService.requireBalance(bd("100"), null))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("insufficient balance")
                .hasMessageContaining("available=null");
    }

    @Test
    void requireBalance_insufficientAvailable_throwsInsufficientBalance() {
        assertThatThrownBy(() -> positionService.requireBalance(bd("100"), bd("50")))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("required=100")
                .hasMessageContaining("available=50");
    }

    @Test
    void requireBalance_sufficientAvailable_doesNotThrow() {
        // available == required 边界也通过(< 严格小于,等于不抛)
        assertThatCode(() -> positionService.requireBalance(bd("100"), bd("100")))
                .doesNotThrowAnyException();
        assertThatCode(() -> positionService.requireBalance(bd("100"), bd("150")))
                .doesNotThrowAnyException();
    }

    // ---------------- findById / findPerpForLiquidation 委托 ----------------

    @Test
    void findById_delegatesToMapper() {
        Position p = existingLongPerp();
        when(positionMapper.findById(42L)).thenReturn(p);

        Position result = positionService.findById(42L);

        assertThat(result).isSameAs(p);
        verify(positionMapper).findById(42L);
    }

    @Test
    void findPerpForLiquidation_delegatesToMapper() {
        Position p = existingLongPerp();
        when(positionMapper.findAllPerpBySymbolAndExchange("BTC/USDT", Exchange.OKX))
                .thenReturn(List.of(p));

        List<Position> result = positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX);

        assertThat(result).containsExactly(p);
        verify(positionMapper).findAllPerpBySymbolAndExchange("BTC/USDT", Exchange.OKX);
    }

    // ---------------- applyFill 10 参 PERP 路径 ----------------

    @Test
    void applyFill_perpOpenLongOnFlat_insertsNewPositionAndReturnsZero() {
        // flat → findByAccountSymbolPosition 返 null → 内存构造 flat + applyPerpDelta + insert
        when(positionMapper.findByAccountSymbolPosition(1L, "BTC/USDT", "LONG", MarginMode.ISOLATED, 10))
                .thenReturn(null);

        BigDecimal pnl = positionService.applyFill(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                bd("0.1"),
                bd("42000"),
                bd("0"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                10,
                MarginMode.ISOLATED);

        // OPEN_* 返 ZERO
        assertThat(pnl).isEqualByComparingTo("0");
        // insert 被调一次,position 已被填充为 LONG
        verify(positionMapper)
                .insert(argThat(p -> Position.SIDE_LONG.equals(p.getSide())
                        && "LONG".equals(p.getPositionSide())
                        && p.getQty().compareTo(bd("0.1")) == 0
                        && p.getLeverage() == 10));
        // flat 路径不走 casUpdate
        verify(positionMapper, never()).casUpdate(any());
    }

    @Test
    void applyFill_perpOpenShortOnExisting_casUpdatesAndReturnsZero() {
        // 已有 SHORT 持仓 → applyPerpDelta 加仓 → casUpdate 成功
        Position existing = existingLongPerp();
        existing.setSide(Position.SIDE_SHORT);
        existing.setPositionSide("SHORT");
        when(positionMapper.findByAccountSymbolPosition(1L, "BTC/USDT", "SHORT", MarginMode.ISOLATED, 10))
                .thenReturn(existing);
        when(positionMapper.casUpdate(existing)).thenReturn(1);

        BigDecimal pnl = positionService.applyFill(
                1L,
                "BTC/USDT",
                OrderSide.SELL,
                bd("0.05"),
                bd("41000"),
                bd("0"),
                MarketType.PERP,
                PositionEffect.OPEN_SHORT,
                10,
                MarginMode.ISOLATED);

        assertThat(pnl).isEqualByComparingTo("0");
        // casUpdate 成功 → version 自增 3→4
        assertThat(existing.getVersion()).isEqualTo(4L);
        verify(positionMapper).casUpdate(existing);
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void applyFill_perpCloseLongOnFlat_throwsRejectFillAndSkipsInsert() {
        // CLOSE_LONG on flat → findByAccountSymbolPosition 返 null → 内存 flat + applyPerpDelta
        // 抛 RejectFillException,不进入 insert
        when(positionMapper.findByAccountSymbolPosition(1L, "BTC/USDT", "LONG", MarginMode.ISOLATED, 10))
                .thenReturn(null);

        assertThatThrownBy(() -> positionService.applyFill(
                        1L,
                        "BTC/USDT",
                        OrderSide.SELL,
                        bd("0.1"),
                        bd("43000"),
                        bd("0"),
                        MarketType.PERP,
                        PositionEffect.CLOSE_LONG,
                        10,
                        MarginMode.ISOLATED))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("PERP CLOSE over-position");

        verify(positionMapper, never()).insert(any());
        verify(positionMapper, never()).casUpdate(any());
    }

    @Test
    void applyFill_perpCasConflictExhaustsRetries_throwsConcurrencyConflict() {
        // 已存在持仓但 casUpdate 三次全返 0(并发 CAS 冲突)→ 抛 ConcurrencyConflictException
        Position existing = existingLongPerp();
        when(positionMapper.findByAccountSymbolPosition(1L, "BTC/USDT", "LONG", MarginMode.ISOLATED, 10))
                .thenReturn(existing);
        when(positionMapper.casUpdate(existing)).thenReturn(0);

        assertThatThrownBy(() -> positionService.applyFill(
                        1L,
                        "BTC/USDT",
                        OrderSide.BUY,
                        bd("0.05"),
                        bd("42500"),
                        bd("0"),
                        MarketType.PERP,
                        PositionEffect.OPEN_LONG,
                        10,
                        MarginMode.ISOLATED))
                .isInstanceOf(ConcurrencyConflictException.class)
                .hasMessageContaining("Position CAS failed")
                .hasMessageContaining("retries")
                .hasMessageContaining("symbol=BTC/USDT");

        // 重试 MAX_CAS_RETRIES=3 次
        verify(positionMapper, times(3)).casUpdate(existing);
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void applyFill_perpInsertDuplicateKey_retriesAndSucceedsOnNextAttempt() {
        // 首次 insert 撞唯一键(DuplicateKeyException)→ continue → 第二轮取已有仓 casUpdate 成功
        Position existing = existingLongPerp();
        when(positionMapper.findByAccountSymbolPosition(1L, "BTC/USDT", "LONG", MarginMode.ISOLATED, 10))
                .thenReturn(null) // 首次:flat
                .thenReturn(existing); // 重试:已有仓
        // insert 返回 void,用 doThrow 桩抛 DuplicateKeyException
        doThrow(new DuplicateKeyException("dup")).when(positionMapper).insert(any());
        when(positionMapper.casUpdate(existing)).thenReturn(1);

        BigDecimal pnl = positionService.applyFill(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                bd("0.05"),
                bd("42500"),
                bd("0"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                10,
                MarginMode.ISOLATED);

        assertThat(pnl).isEqualByComparingTo("0");
        // insert 仅被调一次(首次),随后切到 casUpdate 路径
        verify(positionMapper, times(1)).insert(any());
        verify(positionMapper, times(1)).casUpdate(existing);
        assertThat(existing.getVersion()).isEqualTo(4L);
    }
}
