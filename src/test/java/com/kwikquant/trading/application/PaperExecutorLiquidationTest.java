package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * PaperExecutor.onTicker 强平判定 + markPrice 缓存单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@link PaperExecutor#computeMarkPrice} mid=(bid+ask)/2 fallback last</li>
 *   <li>{@link PaperExecutor#onTicker} 开头强平判定:ISOLATED 走 marginBreached 谓词
 *       (marginBalance = frozenAmount + unrealizedPnl vs maintReq = mark×qty×mmr,
 *       docs/perp-math-spec.md §3.7),不比较存量 liquidation_price 列
 *       → 触发调 {@link ExecutionService#processLiquidation}</li>
 *   <li>保证金未穿仓(markPrice 安全)不调 processLiquidation;资金费侵蚀穿仓时
 *       价格未跌也触发(旧价格比较的 P0 盲区)</li>
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
    private ApplicationEventPublisher publisher;
    private BalanceService balanceService;
    private CrossLiquidationChecker crossChecker;
    private TradingTransactionHelper txHelper;
    private PaperExecutor executor;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        orderMapper = mock(OrderMapper.class);
        executionService = mock(ExecutionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        positionService = mock(PositionService.class);
        publisher = mock(ApplicationEventPublisher.class);
        balanceService = mock(BalanceService.class);
        // 真实 CrossChecker(注入 mock),CROSS 强平链路真实跑(balanceService.fetchBalance mock 仍生效)
        crossChecker = new CrossLiquidationChecker(positionService, accountService, balanceService, executionService);
        txHelper = mock(TradingTransactionHelper.class);
        executor = new PaperExecutor(
                marketDataService,
                orderMapper,
                executionService,
                wsBroadcaster,
                accountService,
                positionService,
                publisher,
                crossChecker,
                txHelper);
    }

    // ---------- markPrice 计算 ----------

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

    // ---------- 强平判定(ISOLATED marginBreached 谓词) ----------
    //
    // 标准场景数值:avg=42000, qty=0.1, frozen=420, mmr=0.005(默认)
    //   LONG  穿仓价 = (4200−420)/(0.1×0.995) = 37989.94974874(mark 低于它 → breach)
    //   SHORT 穿仓价 = (4200+420)/(0.1×1.005) = 45970.14925373(mark 高于它 → breach)
    // 触发判定 = marginBreached(marginBalance, maintReq),不读存量 liquidation_price 列。

    @Test
    void onTicker_longPositionMarginBreached_triggersLiquidation() {
        // LONG markPrice=37000(mid): marginBalance=420+(37000−42000)×0.1=−80 ≤ 0 → 穿仓强平
        Position pos = position(100L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

        // markPrice=37000(mid),positionId=100,triggerOrderId=null(BigDecimal eq scale 陷阱用 argThat)
        verify(executionService)
                .processLiquidation(
                        eq(100L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("37000")) == 0), isNull());
    }

    @Test
    void onTicker_shortPositionMarginBreached_triggersLiquidation() {
        // SHORT markPrice=47000(mid): marginBalance=420+(42000−47000)×0.1=−80 ≤ 0 → 穿仓强平
        Position pos = position(200L, "SHORT", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.BINANCE))
                .thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.BINANCE, new BigDecimal("46900"), new BigDecimal("47100"), new BigDecimal("47000"));

        executor.onTicker(t);

        verify(executionService)
                .processLiquidation(
                        eq(200L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("47000")) == 0), isNull());
    }

    @Test
    void onTicker_longMarginAboveMaintenance_noLiquidation() {
        // LONG markPrice=38000(mid): marginBalance=420−400=20 > maintReq=38000×0.1×0.005=19 → 不强平
        // (38000 高于穿仓价 37989.95,紧绷但未穿)
        Position pos = position(300L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("37900"), new BigDecimal("38100"), new BigDecimal("38000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_shortMarginAboveMaintenance_noLiquidation() {
        // SHORT markPrice=45900(mid): marginBalance=420−390=30 > maintReq=45900×0.1×0.005=22.95 → 不强平
        // (45900 低于穿仓价 45970.15)
        Position pos = position(400L, "SHORT", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("45850"), new BigDecimal("45950"), new BigDecimal("45900"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_flatPositionSkipped_noLiquidation() {
        // qty=0(flat)不判强平
        Position pos = position(500L, "LONG", BigDecimal.ZERO, new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_multiplePositions_onlyBreachedOneLiquidated() {
        // 两仓 @markPrice=37000:LONG mb=−80 ≤ 0 触发;SHORT mb=420+500=920 ≫ maint=18.5 不触发
        Position longPos = position(600L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        Position shortPos = position(601L, "SHORT", new BigDecimal("0.1"), new BigDecimal("420"));
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
        Position pos = position(700L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
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
        Position pos = position(800L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation(any(), any())).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, null, null, null);

        executor.onTicker(t);

        // markPrice null 时连 findPerpForLiquidation 都不调(短路)
        verify(positionService, never()).findPerpForLiquidation(any(), any());
        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_markPriceZero_skipsLiquidationCheck() {
        // last=0 脏数据(bid/ask 缺失)→ markPrice=0 非正:不强平(按 0 价会把所有仓误强平抽干账户)
        Position pos = position(1100L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation(any(), any())).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, null, null, BigDecimal.ZERO);

        executor.onTicker(t);

        verify(positionService, never()).findPerpForLiquidation(any(), any());
        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_fundingErodedMargin_triggersWithoutPriceDrop() {
        // 资金费把保证金侵蚀穿仓(frozen=−50):markPrice=42000(=开仓均价,价格从未下跌)
        // → marginBalance=−50+(42000−42000)×0.1=−50 ≤ 0 → 触发。
        // 旧逻辑比较存量 liqPrice 列,侵蚀场景永不触发(逐仓仓位被资金费放血而不强平的 P0 修复)
        Position pos = position(900L, "LONG", new BigDecimal("0.1"), new BigDecimal("-50"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("41900"), new BigDecimal("42100"), new BigDecimal("42000"));

        executor.onTicker(t);

        verify(executionService)
                .processLiquidation(
                        eq(900L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("42000")) == 0), isNull());
    }

    @Test
    void onTicker_dirtyRowMissingAvgEntryPrice_skipped() {
        // 脏行 qty>0 但 avgEntryPrice=null:unrealizedPnl 不可派生 → 跳过不强平。
        // 若不跳过,frozen=0 时 marginBalance=0 ≤ 0 会按误判强平(下游平仓链路对 avg=null 也会炸)
        Position pos = position(1000L, "LONG", new BigDecimal("0.1"), BigDecimal.ZERO);
        pos.setAvgEntryPrice(null);
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        Ticker t = ticker(Exchange.OKX, new BigDecimal("36900"), new BigDecimal("37100"), new BigDecimal("37000"));

        executor.onTicker(t);

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

    /**
     * ISOLATED 持仓 helper:avg=42000 固定,qty/frozen 传入。liquidationPrice 列按 margin-aware
     * 公式派生写入(仅展示语义——触发判定不读此列,marginBreached 直接看 marginBalance vs maintReq)。
     */
    private static Position position(long id, String positionSide, BigDecimal qty, BigDecimal frozen) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(1L);
        p.setSymbol("BTC/USDT");
        p.setSide("LONG".equals(positionSide) ? Position.SIDE_LONG : Position.SIDE_SHORT);
        p.setPositionSide(positionSide);
        p.setQty(qty);
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setLeverage(10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setFrozenAmount(frozen);
        p.setLiquidationPrice(p.computeLiquidationPrice(null));
        p.setVersion(1L);
        return p;
    }

    // ---------- CROSS 全仓账户级强平 ----------

    private static Position crossPosition(
            long id, String symbol, String positionSide, BigDecimal qty, BigDecimal avgEntry) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(1L);
        p.setSymbol(symbol);
        p.setSide("LONG".equals(positionSide) ? Position.SIDE_LONG : Position.SIDE_SHORT);
        p.setPositionSide(positionSide);
        p.setQty(qty);
        p.setAvgEntryPrice(avgEntry);
        p.setLeverage(10);
        p.setMarginMode(MarginMode.CROSS);
        p.setLiquidationPrice(null); // CROSS 无单仓强平价(账户级 marginRatio 判定)
        p.setFrozenAmount(BigDecimal.ZERO);
        p.setVersion(1L);
        return p;
    }

    @Test
    void onTicker_crossMarginRatioBelowOne_noLiquidation() {
        // account 1 CROSS 仓 BTC qty=0.01 avgEntry=60000, paper_balance.free=1000
        // markPrice=60000 → unrealizedPnl=0, marginBalance=1000, maintMargin=0.01×60000×0.005=3
        // marginRatio=3/1000=0.003 < 1 → 不强平
        Position pos = crossPosition(100L, "BTC/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("60000"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        when(positionService.findCrossPerpByAccount(1L)).thenReturn(List.of(pos));
        ExchangeAccount account = mock(ExchangeAccount.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getExchange()).thenReturn(Exchange.OKX);
        when(accountService.findById(1L)).thenReturn(account);
        BalanceSnapshot snap = new BalanceSnapshot(java.util.Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"))));
        when(balanceService.fetchBalance(eq(1L), eq(1L), eq(MarketType.PERP))).thenReturn(snap);
        Ticker t = ticker(Exchange.OKX, new BigDecimal("59900"), new BigDecimal("60100"), new BigDecimal("60000"));

        executor.onTicker(t);

        verify(executionService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void onTicker_crossMarginBalanceNegative_liquidatesAll() {
        // account 1 CROSS 仓 BTC qty=0.01 avgEntry=60000, paper_balance.free=10
        // markPrice=30000 → unrealizedPnl=(30000-60000)×0.01=-300, marginBalance=10+(-300)=-290<0 → 全平
        Position pos = crossPosition(100L, "BTC/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("60000"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(pos));
        when(positionService.findCrossPerpByAccount(1L)).thenReturn(List.of(pos));
        ExchangeAccount account = mock(ExchangeAccount.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getExchange()).thenReturn(Exchange.OKX);
        when(accountService.findById(1L)).thenReturn(account);
        BalanceSnapshot snap = new BalanceSnapshot(java.util.Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"))));
        when(balanceService.fetchBalance(eq(1L), eq(1L), eq(MarketType.PERP))).thenReturn(snap);
        Ticker t = ticker(Exchange.OKX, new BigDecimal("29900"), new BigDecimal("30100"), new BigDecimal("30000"));

        executor.onTicker(t);

        verify(executionService)
                .processLiquidation(
                        eq(100L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("30000")) == 0), isNull());
    }

    @Test
    void onTicker_crossMultiQuote_bucketsJudgedSeparately() {
        // 按 quote 分桶:USDT 桶健康(free=1000, maint=0.01×60000×0.005=3),
        // USDC 桶穿仓(free=0.1, upl=0, maint=0.01×3000×0.005=0.15 > marginBalance=0.1)
        // → 只强平 USDC 桶的 ETH 仓,BTC 仓不动(旧版跨币相加名义额会错判)
        Position btc = crossPosition(100L, "BTC/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("60000"));
        Position eth = crossPosition(101L, "ETH/USDC", "LONG", new BigDecimal("0.01"), new BigDecimal("3000"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(btc));
        when(positionService.findCrossPerpByAccount(1L)).thenReturn(List.of(btc, eth));
        ExchangeAccount account = mock(ExchangeAccount.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getExchange()).thenReturn(Exchange.OKX);
        when(accountService.findById(1L)).thenReturn(account);
        BalanceSnapshot snap = new BalanceSnapshot(java.util.Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000")),
                "USDC",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("0.1"), BigDecimal.ZERO, new BigDecimal("0.1"))));
        when(balanceService.fetchBalance(eq(1L), eq(1L), eq(MarketType.PERP))).thenReturn(snap);
        crossChecker.updateMarkPrice(Exchange.OKX, "ETH/USDC", new BigDecimal("3000")); // BTC mark 由 onTicker 写入
        Ticker t = ticker(Exchange.OKX, new BigDecimal("59900"), new BigDecimal("60100"), new BigDecimal("60000"));

        executor.onTicker(t);

        verify(executionService)
                .processLiquidation(
                        eq(101L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("3000")) == 0), isNull());
        verify(executionService, never()).processLiquidation(eq(100L), any(), any());
    }

    @Test
    void onTicker_crossMultiSymbol_missingMarkPriceSkipsNotZero() {
        // account 1 两 CROSS 仓:BTC(qty=0.01 avg=60000)+ ETH(qty=0.01 avg=3000),paper_balance.free=10
        // ticker 只 BTC(markPrice=30000)→ BTC_upl=(30000-60000)×0.01=-300,marginBalance=10-300=-290<0 全平触发
        // ETH 无 ticker → markPriceCache 未命中 → 跳过(不按 price=0 强平致账户被错误抽干)
        Position btcPos = crossPosition(100L, "BTC/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("60000"));
        Position ethPos = crossPosition(101L, "ETH/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("3000"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(btcPos));
        when(positionService.findCrossPerpByAccount(1L)).thenReturn(List.of(btcPos, ethPos));
        ExchangeAccount account = mock(ExchangeAccount.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getExchange()).thenReturn(Exchange.OKX);
        when(accountService.findById(1L)).thenReturn(account);
        BalanceSnapshot snap = new BalanceSnapshot(java.util.Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"))));
        when(balanceService.fetchBalance(eq(1L), eq(1L), eq(MarketType.PERP))).thenReturn(snap);
        Ticker t = ticker(Exchange.OKX, new BigDecimal("29900"), new BigDecimal("30100"), new BigDecimal("30000"));

        executor.onTicker(t);

        // BTC 有 markPrice 缓存 → 强平 at 30000;ETH 无缓存 → 跳过(never processLiquidation(101, 0, null))
        verify(executionService)
                .processLiquidation(
                        eq(100L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("30000")) == 0), isNull());
        verify(executionService, never()).processLiquidation(eq(101L), any(), any());
    }

    @Test
    void onTicker_mixedCrossAndIsolatedSameAccount_bothPathsDispatched() {
        // account 1 同 symbol(BTC/USDT)持 CROSS LONG + ISOLATED LONG,free=10。
        // ticker BTC markPrice=30000 → ISOLATED 逐仓:marginBalance=420+(30000−42000)×0.1=−780≤0
        // 触发 per-position 强平(101);
        // CROSS 账户级:marginBalance=10+(30000-60000)×0.01=-290≤0 触发账户级聚合强平(100)。
        // 验同 tick 内两条 dispatch 路径(CROSS 账户级 + ISOLATED 逐仓)都被调且不互相干扰(LOW-3 测试盲区)。
        Position crossPos = crossPosition(100L, "BTC/USDT", "LONG", new BigDecimal("0.01"), new BigDecimal("60000"));
        Position isoPos = position(101L, "LONG", new BigDecimal("0.1"), new BigDecimal("420"));
        when(positionService.findPerpForLiquidation("BTC/USDT", Exchange.OKX)).thenReturn(List.of(crossPos, isoPos));
        when(positionService.findCrossPerpByAccount(1L)).thenReturn(List.of(crossPos));
        ExchangeAccount account = mock(ExchangeAccount.class);
        when(account.getUserId()).thenReturn(1L);
        when(account.getExchange()).thenReturn(Exchange.OKX);
        when(accountService.findById(1L)).thenReturn(account);
        BalanceSnapshot snap = new BalanceSnapshot(java.util.Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"))));
        when(balanceService.fetchBalance(eq(1L), eq(1L), eq(MarketType.PERP))).thenReturn(snap);
        Ticker t = ticker(Exchange.OKX, new BigDecimal("29900"), new BigDecimal("30100"), new BigDecimal("30000"));

        executor.onTicker(t);

        // ISOLATED 逐仓路径(101) + CROSS 账户级路径(100) 都触发,各自 markPrice=30000
        verify(executionService)
                .processLiquidation(
                        eq(100L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("30000")) == 0), isNull());
        verify(executionService)
                .processLiquidation(
                        eq(101L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("30000")) == 0), isNull());
    }
}
