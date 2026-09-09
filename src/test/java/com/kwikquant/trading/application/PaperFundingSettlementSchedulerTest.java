package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.FundingRateKind;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * PaperFundingSettlementScheduler 单测(期次网格语义,V59)。
 *
 * <p>覆盖:单期 SETTLED 结算/catch-up 多期 ASC 严格顺序/标记价行缺失 fallback ticker mid/
 * 标记价全缺严格停止(不跳期漏钱)/floor 边界期次过滤(watermark 与 openedAt 取晚)/存量仓
 * fallback 回看窗/非法 symbol 与 positionSide 脏数据 fail-closed 跳过/PERP+非 flat 过滤。
 * 金额语义单源在 {@code PerpMath.fundingAmount}(docs/perp-math-spec.md §3.8,双侧 fixtures 对拍)。
 */
class PaperFundingSettlementSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-05T16:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-05T16:30:00Z");

    private ExchangeAccountService accountService;
    private PositionService positionService;
    private MarketDataService marketDataService;
    private FundingSettlementService fundingSettlementService;
    private PaperFundingSettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        positionService = mock(PositionService.class);
        marketDataService = mock(MarketDataService.class);
        fundingSettlementService = mock(FundingSettlementService.class);
        scheduler = new PaperFundingSettlementScheduler(
                accountService, positionService, marketDataService, fundingSettlementService);
    }

    // ---------- 单期结算 ----------

    @Test
    void settlePosition_duePeriod_settlesWithSettledRateAndPeriodKey() {
        // watermark=T0,已结算期次 T1(rate=0.0001,mark=60000 行内自带):
        // LONG qty=0.01 → fundingAmount=0.0001×60000×0.01×(−1)=−0.06(多头付)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService)
                .processFundingSettlement(argThat((FundingSettleCommand c) -> c.accountId() == 7L
                        && Long.valueOf(128L).equals(c.positionId())
                        && "BTC/USDT".equals(c.symbol())
                        && c.fundingRate().compareTo(new BigDecimal("0.0001")) == 0
                        && c.qty().compareTo(new BigDecimal("0.01")) == 0
                        && c.fundingAmount().compareTo(new BigDecimal("-0.06")) == 0
                        && T1.equals(c.fundingTime())
                        && c.rateKind() == FundingRateKind.SETTLED
                        && Integer.valueOf(28800).equals(c.intervalSeconds())
                        && c.markPrice().compareTo(new BigDecimal("60000")) == 0
                        && "USDT".equals(c.currency())
                        && c.marginMode() == MarginMode.ISOLATED));
    }

    @Test
    void settlePosition_shortPositiveRate_receives() {
        // SHORT 正费率收:0.0001×60000×0.01×(+1)=+0.06
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_SHORT, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService)
                .processFundingSettlement(
                        argThat((FundingSettleCommand c) -> c.fundingAmount().compareTo(new BigDecimal("0.06")) == 0));
    }

    @Test
    void settlePosition_negativeRate_longReceives() {
        // 负费率多头收:−0.0001×60000×0.01×(−1)=+0.06
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "-0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService)
                .processFundingSettlement(
                        argThat((FundingSettleCommand c) -> c.fundingRate().compareTo(new BigDecimal("-0.0001")) == 0
                                && c.fundingAmount().compareTo(new BigDecimal("0.06")) == 0));
    }

    // ---------- 同期次双行"换代表"防重结(watermark 容差) ----------

    @Test
    void settlePosition_samePeriodReRepresentationWithinTolerance_skipped() {
        // watermark=T1(此前按 PROXY 精确网格时刻结过);回填补上原生漂移行 T1+3min,读侧去重
        // 改推漂移行(EXCHANGE 优先)——仍是同一逻辑期次,在 max(60s, 28800/4)=2h 容差内,不二次扣钱
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(period(T1.plusSeconds(180), "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    @Test
    void settlePosition_nullIntervalDriftRow_usesFloorTolerance_skipped() {
        // 真实管线形态:每分钟 pass 窗口行数不足,漂移行富化不出 interval(相邻差分分钟级 <1h)
        // → interval=null,声明值容差分支不可达。下限容差 59min 兜住(网格 ≥1h 全局假设,
        // 真下一期 ≥3600s);旧实现此形态直接二次扣钱(防线死代码,单测曾喂 28800 假绿)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(period(T1.plusSeconds(180), "0.0001", null, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    @Test
    void settlePosition_samePassDoubleRows_settlesOnceOnly() {
        // 同 pass 窗口同时含网格行与漂移行(读侧 dedupe 因窗口行数不足派生失败而放行):
        // lastSettled 游标随结算推进,第二行落进下限容差 → 只结一次
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", null, "60000"), period(T1.plusSeconds(240), "0.0001", null, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, times(1)).processFundingSettlement(any());
    }

    @Test
    void settlePosition_nullIntervalRealNextPeriod_stillSettles() {
        // 1h 网格真下一期(+3600s > 3540s 下限容差):容差不吞合法期次
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(period(T1.plusSeconds(3600), "0.0001", null, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, times(1)).processFundingSettlement(any());
    }

    @Test
    void settlePosition_nextPeriodBeyondTolerance_settles() {
        // 真下一期(T2 = T1+8h > 容差 2h)照常结算,容差不吞合法期次
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(period(T2, "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService).processFundingSettlement(any());
    }

    // ---------- 每轮限额(数据迟到场景防一次补结数百期) ----------

    @Test
    void settlePosition_perPassCap_defersRemainingToNextPass() {
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(
                period(T1, "0.0001", 28800, "60000"),
                period(T2, "0.0001", 28800, "60000"),
                period(T2.plusSeconds(28800), "0.0001", 28800, "60000"));
        scheduler.maxPeriodsPerPass = 2;

        scheduler.settlePosition(account, pos, T2.plusSeconds(86400));

        // 限额 2:第三期留待下轮(ASC 严格顺序 + watermark 续接,不漏不跳)
        verify(fundingSettlementService, times(2)).processFundingSettlement(any());
    }

    // ---------- catch-up 与严格顺序 ----------

    @Test
    void settlePosition_catchUpSettlesDuePeriodsInAscOrder() {
        // 宕机漏了两期(T1,T2):按 ASC 逐期结算,各期用自己的 settled rate(相邻期符号可翻转)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"), period(T2, "-0.0002", 28800, "50000"));

        scheduler.settlePosition(account, pos, NOW);

        InOrder inOrder = inOrder(fundingSettlementService);
        // T1: 0.0001×60000×0.01×(−1) = −0.06
        inOrder.verify(fundingSettlementService)
                .processFundingSettlement(argThat((FundingSettleCommand c) ->
                        T1.equals(c.fundingTime()) && c.fundingAmount().compareTo(new BigDecimal("-0.06")) == 0));
        // T2: −0.0002×50000×0.01×(−1) = +0.10(负费率多头收)
        inOrder.verify(fundingSettlementService)
                .processFundingSettlement(argThat((FundingSettleCommand c) ->
                        T2.equals(c.fundingTime()) && c.fundingAmount().compareTo(new BigDecimal("0.1")) == 0));
        verifyNoMoreInteractions(ignoreStubs(fundingSettlementService));
    }

    @Test
    void settlePosition_markPriceMissingOnRow_fallsBackToTickerMid() {
        // funding_rates 行无 mark_price(OKX 采集常态)→ 用当前 ticker mid=(59900+60100)/2=60000 近似
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, null));
        when(marketDataService.getLatestTicker(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(ticker("59900", "60100", "60050"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService)
                .processFundingSettlement(
                        argThat((FundingSettleCommand c) -> c.markPrice().compareTo(new BigDecimal("60000")) == 0
                                && c.fundingAmount().compareTo(new BigDecimal("-0.06")) == 0));
    }

    @Test
    void settlePosition_noMarkPriceAnywhere_stopsWithoutSettling() {
        // 行无 mark_price 且 ticker 不可得 → 严格停止不结算(不用 0/脏价落账),等下一轮
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, null));
        when(marketDataService.getLatestTicker(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(null);

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    @Test
    void settlePosition_secondPeriodUnsettleable_stopsStrictlyNoSkip() {
        // 两期:T1 可结(mark 行内自带),T2 不可结(mark 缺 + ticker 缺)→ 只结 T1 后停止,
        // 不跳过 T2 继续(跳期=静默漏钱);T2 留待下一轮从本期续结
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"), period(T2, "0.0001", 28800, null));
        when(marketDataService.getLatestTicker(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(null);

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, times(1))
                .processFundingSettlement(argThat((FundingSettleCommand c) -> T1.equals(c.fundingTime())));
    }

    @Test
    void settlePosition_settleThrows_stopsRemainingPeriods() {
        // 第一期落账抛异常(DB/CAS)→ 后续期次不再结(严格顺序),异常上抛由 settleAccount 捕获
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"), period(T2, "0.0001", 28800, "60000"));
        doThrow(new RuntimeException("db down")).when(fundingSettlementService).processFundingSettlement(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.settlePosition(account, pos, NOW))
                .isInstanceOf(RuntimeException.class);

        verify(fundingSettlementService, times(1)).processFundingSettlement(any());
    }

    // ---------- floor 边界 ----------

    @Test
    void settlePosition_periodAtWatermarkBoundary_skipped() {
        // [floor, now) 查询含边界行:funding_time == watermark 的期次已结算过,过滤不重复结
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", null);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(period(T1, "0.0001", 28800, "60000"), period(T2, "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, times(1))
                .processFundingSettlement(argThat((FundingSettleCommand c) -> T2.equals(c.fundingTime())));
    }

    @Test
    void settlePosition_openedAtLaterThanWatermark_usedAsFloor() {
        // 平仓重开场景:watermark=T0(旧仓留下的),openedAt=T1(重开时刻)→ floor=T1,
        // T1 期(开仓时刻边界,仓位刚打开不参与该期)不结,只结 T2
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T1);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"), period(T2, "0.0001", 28800, "60000"));

        scheduler.settlePosition(account, pos, NOW);

        verify(marketDataService).findSettledFundingPeriods(eq(Exchange.OKX), eq("BTC/USDT"), eq(T1), eq(NOW));
        verify(fundingSettlementService, times(1))
                .processFundingSettlement(argThat((FundingSettleCommand c) -> T2.equals(c.fundingTime())));
    }

    @Test
    void settlePosition_noWatermarkNoOpenedAt_fallsBackToEightHourWindow() {
        // 存量仓(V59 前开仓且从未结算):floor = now−8h,只回看最近一期,不回收全部回填历史
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", null);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(null);

        scheduler.settlePosition(account, pos, NOW);

        Instant expectedFloor = NOW.minusSeconds(PaperFundingSettlementScheduler.FALLBACK_WINDOW_SECONDS);
        verify(marketDataService)
                .findSettledFundingPeriods(eq(Exchange.OKX), eq("BTC/USDT"), eq(expectedFloor), eq(NOW));
    }

    @Test
    void settlePosition_noDuePeriods_noSettlement() {
        // 期序列无新期次(常态:每分钟扫描,8h 才有一期)→ 不结算
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        when(fundingSettlementService.findLastFundingTime(7L, 128L)).thenReturn(T1);
        stubPeriods(); // 空

        scheduler.settlePosition(account, pos, NOW);

        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    // ---------- fail-closed 与过滤 ----------

    @Test
    void settlePosition_symbolWithoutQuoteSegment_skipsFailClosed() {
        // 非法 symbol(无 '/' 派生不出 quote 币种):fail-closed 跳过,不查期次不落账
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(132L, "BTCUSDT", Position.SIDE_LONG, "0.01", T0);

        scheduler.settlePosition(account, pos, NOW);

        verify(marketDataService, never()).findSettledFundingPeriods(any(), anyString(), any(), any());
        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    @Test
    void settleAccount_filtersPerpNonFlatAndSettlesOnlyEligible() {
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position perpLong = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        Position spotPos = spotPosition(129L, "ETH/USDT"); // SPOT marginMode=null,跳过
        Position perpFlat = perpPosition(130L, "ETH/USDT", Position.SIDE_LONG, "0", T0); // flat,跳过
        when(positionService.findByAccount(7L)).thenReturn(List.of(perpLong, spotPos, perpFlat));
        when(fundingSettlementService.findLastFundingTime(eq(7L), anyLong())).thenReturn(T0);
        when(marketDataService.findSettledFundingPeriods(eq(Exchange.OKX), anyString(), any(), any()))
                .thenReturn(List.of(period(T1, "0.0001", 28800, "60000")));

        scheduler.settleAccount(account, NOW);

        verify(fundingSettlementService, times(1)).processFundingSettlement(any(FundingSettleCommand.class));
    }

    @Test
    void settleAccount_invalidPositionSide_skipsFailClosed() {
        // positionSide 非 LONG/SHORT(null/脏数据行):内核抛 INVALID_SIDE → settleAccount 捕获跳过,
        // 不按默认 LONG 猜方向结算(猜错即静默金融方向翻转),也不阻断其他仓
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position dirty = perpPosition(131L, "BTC/USDT", Position.SIDE_LONG, "0.01", T0);
        dirty.setPositionSide(null);
        when(positionService.findByAccount(7L)).thenReturn(List.of(dirty));
        when(fundingSettlementService.findLastFundingTime(7L, 131L)).thenReturn(T0);
        stubPeriods(period(T1, "0.0001", 28800, "60000"));

        scheduler.settleAccount(account, NOW); // 不上抛

        verify(fundingSettlementService, never()).processFundingSettlement(any());
    }

    // ---------- 工具 ----------

    @Test
    void laterOf_picksLaterAndHandlesNulls() {
        assertThat(PaperFundingSettlementScheduler.laterOf(T0, T1)).isEqualTo(T1);
        assertThat(PaperFundingSettlementScheduler.laterOf(T1, T0)).isEqualTo(T1);
        assertThat(PaperFundingSettlementScheduler.laterOf(null, T0)).isEqualTo(T0);
        assertThat(PaperFundingSettlementScheduler.laterOf(T0, null)).isEqualTo(T0);
        assertThat(PaperFundingSettlementScheduler.laterOf(null, null)).isNull();
    }

    @Test
    void quoteCurrency_derivesFromCcxtSymbol() {
        assertThat(PaperFundingSettlementScheduler.quoteCurrency("BTC/USDT")).isEqualTo("USDT");
        // CCXT PERP 线性结算后缀形式
        assertThat(PaperFundingSettlementScheduler.quoteCurrency("BTC/USDT:USDT"))
                .isEqualTo("USDT");
        assertThat(PaperFundingSettlementScheduler.quoteCurrency("ETH/USDC")).isEqualTo("USDC");
        // 非法形态一律 null(fail-closed)
        assertThat(PaperFundingSettlementScheduler.quoteCurrency("BTCUSDT")).isNull();
        assertThat(PaperFundingSettlementScheduler.quoteCurrency("BTC/")).isNull();
        assertThat(PaperFundingSettlementScheduler.quoteCurrency(null)).isNull();
    }

    // ---------- helpers ----------

    private void stubPeriods(FundingRatePeriod... periods) {
        when(marketDataService.findSettledFundingPeriods(eq(Exchange.OKX), eq("BTC/USDT"), any(), any()))
                .thenReturn(List.of(periods));
    }

    private static FundingRatePeriod period(
            Instant fundingTime, String settledRate, Integer intervalSeconds, String markPrice) {
        return new FundingRatePeriod(
                fundingTime,
                new BigDecimal(settledRate),
                null,
                intervalSeconds,
                markPrice != null ? new BigDecimal(markPrice) : null,
                "EXCHANGE");
    }

    private static Ticker ticker(String bid, String ask, String last) {
        return new Ticker(
                Exchange.OKX,
                MarketType.PERP,
                "BTC/USDT",
                new BigDecimal(last),
                new BigDecimal(bid),
                new BigDecimal(ask),
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

    private ExchangeAccount paperAccount(long id, Exchange exchange) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setExchange(exchange);
        a.setPaperTrading(true);
        return a;
    }

    private Position perpPosition(long id, String symbol, String side, String qty, Instant openedAt) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(7L);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setPositionSide("long".equals(side) ? "LONG" : "SHORT");
        p.setQty(new BigDecimal(qty));
        p.setAvgEntryPrice(new BigDecimal("60000"));
        p.setLeverage(10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setFrozenAmount(BigDecimal.ZERO);
        p.setOpenedAt(openedAt);
        return p;
    }

    private Position spotPosition(long id, String symbol) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(7L);
        p.setSymbol(symbol);
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("0.1"));
        p.setMarginMode(null); // SPOT
        return p;
    }
}
