package com.kwikquant.account.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.account.domain.PaperBalance;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * PaperBalanceAdapter 单元测试(mock PaperBalanceMapper)。精准分支覆盖:
 * fetch/initBalance/freeze(成功/不足/无行/零额/CAS 重试/耗尽)/unfreeze(成功/无行/超额只释放实际 used)/
 * applyFill(BUY/SELL/null fee/非法 symbol)/applyDelta 撞键重试/reset。
 */
class PaperBalanceAdapterTest {

    /** 默认测试配置(USDT-only,初始 10 万),复用避免每个测试重复构造 props。 */
    private static final QuoteCurrencyProperties DEFAULT_PROPS =
            new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));

    private PaperBalanceMapper mapper;
    private PaperBalanceAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(PaperBalanceMapper.class);
        adapter = new PaperBalanceAdapter(mapper, DEFAULT_PROPS);
    }

    /** BigDecimal 相等比较(argThat lambda 里用,assertj 的 isEqualByComparingTo 不可用)。 */
    private static boolean eq(BigDecimal actual, String expected) {
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private PaperBalance row(String currency, String free, String used, String total, long version) {
        PaperBalance b = new PaperBalance();
        b.setId(1L);
        b.setAccountId(10L);
        b.setCurrency(currency);
        b.setFree(new BigDecimal(free));
        b.setUsed(new BigDecimal(used));
        b.setTotal(new BigDecimal(total));
        b.setVersion(version);
        return b;
    }

    // --- fetch ---
    @Test
    void fetch_assemblesSnapshotFromRows() {
        when(mapper.findByAccount(10L))
                .thenReturn(List.of(row("USDT", "100000", "0", "100000", 0), row("BTC", "0.5", "0", "0.5", 0)));
        ExchangeAccount account = new ExchangeAccount();
        account.setId(10L);
        account.setExchange(Exchange.BINANCE);
        account.setPaperTrading(true);

        BalanceSnapshot snap = adapter.fetch(account);

        assertThat(snap.currencies()).hasSize(2);
        assertThat(snap.currencies().get("USDT").free()).isEqualByComparingTo("100000");
        assertThat(snap.currencies().get("BTC").total()).isEqualByComparingTo("0.5");
    }

    // --- initBalance ---
    @Test
    void initBalance_insertsUsdt100k() {
        adapter.initBalance(10L, "USDT");

        verify(mapper)
                .insert(argThat(b -> b.getAccountId() == 10L
                        && "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "100000")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "100000")
                        && b.getVersion() == 0));
    }

    @Test
    void initBalance_duplicateKeyIsIdempotent() {
        doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any(PaperBalance.class));

        assertThatCode(() -> adapter.initBalance(10L, "USDT")).doesNotThrowAnyException();
    }

    @Test
    void initBalance_withUsdcCurrency_insertsUsdcRowWithConfiguredAmount() {
        // 注:PaperBalanceAdapter 现在注入 QuoteCurrencyProperties(构造加参)
        QuoteCurrencyProperties props = new QuoteCurrencyProperties(List.of("USDC"), new BigDecimal("50000"));
        PaperBalanceAdapter usdcAdapter = new PaperBalanceAdapter(mapper, props);

        usdcAdapter.initBalance(1L, "USDC");

        verify(mapper)
                .insert(argThat(b -> b.getCurrency().equals("USDC")
                        && b.getFree().compareTo(new BigDecimal("50000")) == 0
                        && b.getTotal().compareTo(new BigDecimal("50000")) == 0));
    }

    // --- freeze ---
    @Test
    void freeze_success_movesFreeToUsed() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.freeze(10L, "USDT", new BigDecimal("1000"));

        verify(mapper)
                .casUpdate(argThat(
                        b -> eq(b.getFree(), "99000") && eq(b.getUsed(), "1000") && eq(b.getTotal(), "100000")));
    }

    @Test
    void freeze_insufficient_throws() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "500", "0", "500", 5));

        assertThatThrownBy(() -> adapter.freeze(10L, "USDT", new BigDecimal("1000")))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("insufficient");
        verify(mapper, never()).casUpdate(any(PaperBalance.class));
    }

    @Test
    void freeze_rowAbsent_throws() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(null);

        assertThatThrownBy(() -> adapter.freeze(10L, "USDT", new BigDecimal("1000")))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void freeze_zeroAmount_isNoop() {
        adapter.freeze(10L, "USDT", BigDecimal.ZERO);

        verify(mapper, never()).findByAccountAndCurrency(anyLong(), anyString());
        verify(mapper, never()).casUpdate(any(PaperBalance.class));
    }

    @Test
    void freeze_casConflictRetriesAndSucceeds() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(0, 1);

        adapter.freeze(10L, "USDT", new BigDecimal("1000"));

        verify(mapper, times(2)).casUpdate(any(PaperBalance.class));
    }

    @Test
    void freeze_casConflictExhausted_throws() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(0);

        assertThatThrownBy(() -> adapter.freeze(10L, "USDT", new BigDecimal("1000")))
                .isInstanceOf(ResourceStateConflictException.class);
    }

    // --- unfreeze ---
    @Test
    void unfreeze_success_movesUsedToFree() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "99000", "1000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.unfreeze(10L, "USDT", new BigDecimal("1000"));

        verify(mapper).casUpdate(argThat(b -> eq(b.getFree(), "100000") && eq(b.getUsed(), "0")));
    }

    @Test
    void unfreeze_rowAbsent_isIdempotent() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(null);

        assertThatCode(() -> adapter.unfreeze(10L, "USDT", new BigDecimal("1000")))
                .doesNotThrowAnyException();
        verify(mapper, never()).casUpdate(any(PaperBalance.class));
    }

    @Test
    void unfreeze_whenRequestedExceedsUsed_releasesOnlyActualUsed() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "99000", "100", "99100", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.unfreeze(10L, "USDT", new BigDecimal("500"));

        verify(mapper)
                .casUpdate(argThat(b -> eq(b.getFree(), "99100") && eq(b.getUsed(), "0") && eq(b.getTotal(), "99100")));
    }

    // --- applyFill PERP CLOSE 穿蚀旁路(marginDelta 为正 → release 为负)---
    @Test
    void applyFill_perpClose_depletedPositiveMarginDelta_negativeReleaseConserved() {
        // 穿蚀仓手动全平:applyPerpDelta 旁路返 marginDelta=+50(=−frozen),CLOSE 分支
        // release=−marginDelta=−50 → free −= 50+fee(吸收缺口)、used += 50(归零)、total −= fee
        // (侵蚀结算时已扣过)。若有人给 release 加 abs()/clamp,此用例红——三桶守恒的机器守护。
        // 起始:free 995, used -50, total 945(侵蚀后形态)
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "995", "-50", "945", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                com.kwikquant.shared.types.OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("41000"),
                new BigDecimal("2"),
                null,
                com.kwikquant.shared.types.MarketType.PERP,
                com.kwikquant.shared.types.PositionEffect.CLOSE_LONG,
                com.kwikquant.shared.types.MarginMode.ISOLATED,
                new BigDecimal("50"));

        // free += (release − fee) = (−50 − 2) = 943;used −= release = −50+50 = 0;total −= fee = 943
        verify(mapper)
                .casUpdate(argThat(b -> eq(b.getFree(), "943") && eq(b.getUsed(), "0") && eq(b.getTotal(), "943")));
    }

    // --- applyDepletedMarginRelease(穿蚀仓强平负释放额) ---
    @Test
    void applyDepletedMarginRelease_negative_movesUsedTowardZeroAndFreeAbsorbs() {
        // 资金费侵蚀后 used=-50(幻影负锁定),total 已随侵蚀减少:free 99950, used -50, total 99900
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "99950", "-50", "99900", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyDepletedMarginRelease(10L, "USDT", new BigDecimal("-50"));

        // used += 50 归零,free −= 50 吸收缺口,total 不变(守恒)
        verify(mapper)
                .casUpdate(argThat(b -> eq(b.getFree(), "99900") && eq(b.getUsed(), "0") && eq(b.getTotal(), "99900")));
    }

    @Test
    void applyDepletedMarginRelease_nonNegative_isNoop() {
        adapter.applyDepletedMarginRelease(10L, "USDT", new BigDecimal("50"));
        adapter.applyDepletedMarginRelease(10L, "USDT", BigDecimal.ZERO);
        verify(mapper, never()).casUpdate(any(PaperBalance.class));
    }

    // --- applyFill BUY ---
    @Test
    void applyFill_buy_debitsQuoteAndCreditsBase() {
        // BUY 0.1 BTC @ 50000,fee 5,冻结量=成交价*数量(无漂移场景,同 LIMIT 单)
        // quote(USDT): free-=5, used-=5000, total-=5005
        // base(BTC): 行不存在 → insert free=0.1 total=0.1
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(null);
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.SPOT,
                null,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "94995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "94995")));
        verify(mapper)
                .insert(argThat(
                        b -> "BTC".equals(b.getCurrency()) && eq(b.getFree(), "0.1") && eq(b.getTotal(), "0.1")));
    }

    /**
     * MARKET 单场景：冻结时按 ticker.last()=50000 估价冻了 5000，但实际成交价是 50100(ask，比冻结价高)。
     * 解冻量必须用真实冻结的 5000，不能用成交价重算的 5010——否则 used 会残留 -10（凭空多出可用余额），
     * 差价通过 free 结算：free 只应该比"无漂移"场景少 (5010-5000)=10（多花的部分），不多不少。
     */
    @Test
    void applyFill_buy_withFrozenAmountDifferentFromActualCost_reconcilesThroughFree() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(null);
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50100"), // 实际成交价(ask)，高于冻结时估价
                new BigDecimal("5"),
                new BigDecimal("5000"), // 冻结时按 last=50000 估的量
                MarketType.SPOT,
                null,
                null,
                null);

        // actualCost = 50100*0.1 = 5010；releaseFromUsed = 5000（真实冻结量）
        // dUsed = -5000 → used: 5000-5000=0（精确清零，不残留）
        // dFree = 5000-5010-5 = -15 → free: 95000-15=94985
        // dTotal = -(5010+5) = -5015 → total: 100000-5015=94985
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "94985")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "94985")));
    }

    /** 没有冻结量记录的历史订单（迁移前建的）：退回旧逻辑，直接用成交价*数量当解冻量。 */
    @Test
    void applyFill_buy_withNullFrozenAmount_fallsBackToActualCost() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(null);
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                null,
                MarketType.SPOT,
                null,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "94995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "94995")));
    }

    // --- applyFill SELL ---
    @Test
    void applyFill_sell_debitsBaseAndCreditsQuote() {
        // SELL 0.1 BTC @ 50000,fee 5
        // base(BTC): free 不变, used-=0.1, total-=0.1
        // quote(USDT): free+=5000-5=4995, total+=4995
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(row("BTC", "0.4", "0.1", "0.5", 5));
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "0", "95000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                null, // SELL 不看这个参数，冻结的是 base 数量，没有价格漂移问题
                MarketType.SPOT,
                null,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "BTC".equals(b.getCurrency())
                        && eq(b.getFree(), "0.4")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "0.4")));
        verify(mapper)
                .casUpdate(argThat(
                        b -> "USDT".equals(b.getCurrency()) && eq(b.getFree(), "99995") && eq(b.getTotal(), "99995")));
    }

    @Test
    void applyFill_nullFee_treatedAsZero() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(null);
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                null,
                new BigDecimal("5000"),
                MarketType.SPOT,
                null,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "95000")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "95000")));
    }

    @Test
    void applyFill_invalidSymbol_throws() {
        assertThatThrownBy(() -> adapter.applyFill(
                        10L,
                        OrderSide.BUY,
                        "BADFORMAT",
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        null,
                        MarketType.SPOT,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- applyDelta DuplicateKey 重试 ---
    @Test
    void applyFill_baseInsertDuplicateKey_retriesAndUpdates() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.findByAccountAndCurrency(10L, "BTC")).thenReturn(null).thenReturn(row("BTC", "0", "0", "0", 0));
        doThrow(new DuplicateKeyException("dup")).doNothing().when(mapper).insert(any(PaperBalance.class));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.SPOT,
                null,
                null,
                null);

        verify(mapper, times(2)).findByAccountAndCurrency(10L, "BTC");
        verify(mapper).casUpdate(argThat(b -> "BTC".equals(b.getCurrency()) && eq(b.getFree(), "0.1")));
    }

    // --- applyFill PERP OPEN (释放保证金 + 扣 fee,不碰 base) ---
    @Test
    void applyFill_perpOpenLong_releasesFrozenAndDeductsFee() {
        // OPEN_LONG:0.1 BTC @ 50000,frozenQuote 5000,fee 5
        // quote(USDT): free += 5000-5=4995, used -= 5000, total -= 5
        // 不查 base / 不 insert base
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "99995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "99995")));
        verify(mapper, never()).findByAccountAndCurrency(10L, "BTC");
        verify(mapper, never()).insert(argThat(b -> "BTC".equals(b.getCurrency())));
    }

    @Test
    void applyFill_perpOpenShort_releasesFrozenAndDeductsFee() {
        // OPEN_SHORT:side=SELL 但 PERP 开仓逻辑相同(释放 frozen + 扣 fee,不看 side)
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_SHORT,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "99995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "99995")));
        verify(mapper, never()).insert(argThat(b -> "BTC".equals(b.getCurrency())));
    }

    @Test
    void applyFill_perpOpenNullFee_treatedAsZero() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                null,
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                null,
                null);

        // fee null→0:free += 5000, used -= 5000, total 不变
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "100000")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "100000")));
    }

    @Test
    void applyFill_perpOpenNullFrozenAmount_fallsBackToPriceQty() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                null,
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                null,
                null);

        // frozen null → price*qty=5000 顶替,等价 frozen=5000
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "99995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "99995")));
    }

    // --- applyFill PERP CLOSE (只扣 fee;方向性 PnL 由 applyPnlSettlement 单独算) ---
    @Test
    void applyFill_perpCloseLong_deductsFeeFromCash() {
        // 回归:平仓成交的 fee 必须从现金扣除,否则 paper_balances 相对净口径账本
        // (fills.realized_pnl_delta / positions.realized_pnl)虚高 Σ(平仓 fee)。
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                null,
                null);

        // fee=5:free -= 5, used 不变(平仓不冻保证金), total -= 5
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "99995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "99995")));
    }

    @Test
    void applyFill_perpCloseShort_deductsFeeFromCash() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_SHORT,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "99995")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "99995")));
    }

    @Test
    void applyFill_perpCloseZeroOrNullFee_isTrueNoop() {
        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                null,
                null);
        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                null,
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_SHORT,
                null,
                null);

        verify(mapper, never()).findByAccountAndCurrency(anyLong(), anyString());
        verify(mapper, never()).casUpdate(any(PaperBalance.class));
        verify(mapper, never()).insert(any(PaperBalance.class));
    }

    @Test
    void applyFill_perpCloseNegativeFee_creditsRebateToCash() {
        // fee=-1(返佣):negate 后为正,返佣入账 free/total,与 OPEN 分支符号语义一致
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100000", "0", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("-1"),
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                null,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "100001")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "100001")));
    }

    // --- applyFill PERP ISOLATED (锁定式:实际保证金留 used,估算差额释放回 free) ---
    @Test
    void applyFill_perpOpenIsolated_locksActualMarginInUsed() {
        // OPEN_LONG ISOLATED:估算冻结 E=5000,内核实际保证金 A=4200(成交价低于估价),fee=5
        // free += E−A−fee = 795, used += A−E = −800(锁定额从估算 5000 变为实际 4200), total −= 5
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                MarginMode.ISOLATED,
                new BigDecimal("4200"));

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "95795")
                        && eq(b.getUsed(), "4200")
                        && eq(b.getTotal(), "99995")));
    }

    @Test
    void applyFill_perpOpenIsolated_actualExceedsEstimate_freeAbsorbsShortfall() {
        // MARKET 单成交价高于冻结估价:A=5200 > E=5000 → free 被多扣 205(100→−105,短暂为负)。
        // 撮合已发生必须记账(fail-closed 风控 80% 缓冲使该场景罕见),不做拒绝
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "100", "5000", "5100", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("52000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                MarginMode.ISOLATED,
                new BigDecimal("5200"));

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "-105")
                        && eq(b.getUsed(), "5200")
                        && eq(b.getTotal(), "5095")));
    }

    @Test
    void applyFill_perpOpenIsolated_nullMarginDelta_locksEstimateAmount() {
        // 历史 FillCommand 无 marginDelta → 退化 A=E:估算额全额锁定,只扣 fee
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "5000", "100000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                MarginMode.ISOLATED,
                null);

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "94995")
                        && eq(b.getUsed(), "5000")
                        && eq(b.getTotal(), "99995")));
    }

    @Test
    void applyFill_perpCloseIsolated_unlocksMarginToFree() {
        // CLOSE_LONG ISOLATED:内核释放额 −marginDelta=420 从 used 解锁回 free,fee=5 扣 free/total
        // free += 420−5=415, used −= 420, total −= 5
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "95000", "420", "95420", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("43000"),
                new BigDecimal("5"),
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                MarginMode.ISOLATED,
                new BigDecimal("-420"));

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "95415")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "95415")));
    }

    @Test
    void applyFill_perpCloseIsolated_zeroReleaseZeroFee_isTrueNoop() {
        // 全平释放额 0(frozen 已被资金费侵蚀为 0)+ fee 0 → 不动账(不空转 CAS)
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 5));

        adapter.applyFill(
                10L,
                OrderSide.SELL,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("43000"),
                BigDecimal.ZERO,
                null,
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                MarginMode.ISOLATED,
                BigDecimal.ZERO);

        verify(mapper, never()).casUpdate(any(PaperBalance.class));
    }

    // --- applyIsolatedFundingErosion (ISOLATED 资金费侵蚀/增厚仓位保证金池) ---
    @Test
    void applyIsolatedFundingErosion_negative_debitsUsedAndTotalNotFree() {
        // 资金费付出(amount=−50):used/total 减,free 不动(逐仓=仓位保证金支付,不动账户可用)
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "420", "1420", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyIsolatedFundingErosion(10L, "USDT", new BigDecimal("-50"));

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "1000")
                        && eq(b.getUsed(), "370")
                        && eq(b.getTotal(), "1370")));
    }

    @Test
    void applyIsolatedFundingErosion_positive_creditsUsedAndTotal() {
        // 资金费收入(amount=+30):used/total 增(保证金增厚),free 不动
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "420", "1420", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyIsolatedFundingErosion(10L, "USDT", new BigDecimal("30"));

        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "1000")
                        && eq(b.getUsed(), "450")
                        && eq(b.getTotal(), "1450")));
    }

    // --- applyPnlSettlement (PERP CLOSE_* 平仓 PnL 结算) ---
    @Test
    void applyPnlSettlement_positivePnl_creditsFreeAndTotal() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyPnlSettlement(10L, "USDT", new BigDecimal("500"));

        // free += 500, used 不变, total += 500
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "1500")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "1500")));
    }

    @Test
    void applyPnlSettlement_negativePnl_debitsFreeAndTotal() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyPnlSettlement(10L, "USDT", new BigDecimal("-300"));

        // free -= 300, used 不变, total -= 300
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "700")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "700")));
    }

    @Test
    void applyPnlSettlement_rowAbsent_insertsNewRow() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(null);
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyPnlSettlement(10L, "USDT", new BigDecimal("500"));

        verify(mapper)
                .insert(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "500")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "500")));
    }

    // --- applyLiquidationDelta (强平扣减,负余额保护) ---
    @Test
    void applyLiquidationDelta_sufficientBalance_normalDeduction() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyLiquidationDelta(10L, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        // free 1000-500=500, used 不变, total 1000-500=500
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "500")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "500")));
    }

    @Test
    void applyLiquidationDelta_insufficientBalance_clampsToZero() {
        // free=300,扣 -500 会变 -200 → clamp dFree=-300,dTotal=-300
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "300", "0", "300", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyLiquidationDelta(10L, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        // free 归 0,total 跟 free 走 → 0(原 300 - 300)
        verify(mapper)
                .casUpdate(argThat(b -> "USDT".equals(b.getCurrency())
                        && eq(b.getFree(), "0")
                        && eq(b.getUsed(), "0")
                        && eq(b.getTotal(), "0")));
    }

    @Test
    void applyLiquidationDelta_rowAbsent_isNoop() {
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(null);

        adapter.applyLiquidationDelta(10L, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        verify(mapper, never()).casUpdate(any(PaperBalance.class));
        verify(mapper, never()).insert(any(PaperBalance.class));
    }

    @Test
    void applyLiquidationDelta_exactZero_noClamp() {
        // free=500,扣 -500 正好归 0,不触发 clamp(free+dFree=0 不 <0)
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "500", "0", "500", 5));
        when(mapper.casUpdate(any(PaperBalance.class))).thenReturn(1);

        adapter.applyLiquidationDelta(10L, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        verify(mapper)
                .casUpdate(
                        argThat(b -> "USDT".equals(b.getCurrency()) && eq(b.getFree(), "0") && eq(b.getTotal(), "0")));
    }

    // --- reset ---
    @Test
    void reset_deletesAllAndReinitsUsdt() {
        adapter.reset(10L, "USDT");

        verify(mapper).deleteByAccount(10L);
        verify(mapper).insert(argThat(b -> "USDT".equals(b.getCurrency()) && eq(b.getFree(), "100000")));
    }

    @Test
    void reset_withCurrency_reinsertsThatCurrency() {
        QuoteCurrencyProperties props = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        PaperBalanceAdapter usdtAdapter = new PaperBalanceAdapter(mapper, props);

        usdtAdapter.reset(1L, "USDT");

        verify(mapper).deleteByAccount(1L);
        verify(mapper).insert(argThat(b -> b.getCurrency().equals("USDT")));
    }

    // --- applyFundingSettlement(PAPER 资金费率 8h 结算) ---
    @Test
    void applyFundingSettlement_negative_paysFromFree() {
        // 负 fundingAmount=付钱:free 1000→995, total 1000→995, used 不变=0
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 1));
        when(mapper.casUpdate(any())).thenReturn(1);

        adapter.applyFundingSettlement(10L, "USDT", new BigDecimal("-5"));

        verify(mapper)
                .casUpdate(argThat(b -> eq(b.getFree(), "995") && eq(b.getTotal(), "995") && eq(b.getUsed(), "0")));
    }

    @Test
    void applyFundingSettlement_positive_receivesToFree() {
        // 正 fundingAmount=收钱:free 1000→1005, total 1000→1005
        when(mapper.findByAccountAndCurrency(10L, "USDT")).thenReturn(row("USDT", "1000", "0", "1000", 1));
        when(mapper.casUpdate(any())).thenReturn(1);

        adapter.applyFundingSettlement(10L, "USDT", new BigDecimal("5"));

        verify(mapper)
                .casUpdate(argThat(b -> eq(b.getFree(), "1005") && eq(b.getTotal(), "1005") && eq(b.getUsed(), "0")));
    }
}
