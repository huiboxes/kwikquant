package com.kwikquant.ai.application;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.TradeRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ReportContextBuilder 单测:指标口径换算、曲线降采样、成交聚合、截断兜底。
 * 纯文本组装逻辑,无 IO,直接断言输出字符串。
 */
class ReportContextBuilderTest {

    @Test
    void build_rendersMetricsWithUnitConversion() {
        // 比率 → 百分比(totalReturn 带符号,maxDrawdown/winRate 无符号);null 指标 → n/a
        BacktestReport r = report();
        r.setTotalReturn(new BigDecimal("0.1234"));
        r.setMaxDrawdown(new BigDecimal("0.085"));
        r.setWinRate(new BigDecimal("0.55"));
        r.setSharpeRatio(new BigDecimal("1.23"));
        r.setProfitFactor(new BigDecimal("1.8"));
        r.setTotalTrades(120);
        r.setAvgTradeDurationSeconds(3600);

        String text = ReportContextBuilder.build(r, List.of(), List.of());

        assertTrue(text.contains("Backtest report context (reportId=95)"));
        assertTrue(text.contains("name: MA 双均线回测"));
        assertTrue(text.contains("symbol: BTC/USDT, timeframe: 1h"));
        assertTrue(text.contains("parameters: {\"initial_capital\":10000}"));
        assertTrue(text.contains("totalReturn=+12.34%"), "totalReturn 应带符号百分比");
        assertTrue(text.contains("maxDrawdown=8.50% (peak-to-trough)"), "maxDrawdown 为幅度,不带 + 号");
        assertTrue(text.contains("winRate=55.00%"));
        assertTrue(text.contains("sharpeRatio=1.23"));
        assertTrue(text.contains("profitFactor=1.8"));
        assertTrue(text.contains("totalTrades=120"));
        assertTrue(text.contains("avgTradeDuration=1h 0m 0s"));
        assertTrue(text.contains("请基于以上回测数据做解读"), "解读指令在场");
    }

    @Test
    void build_nullMetricsRenderNa() {
        // sharpe/profitFactor 可空(低波动/无亏损单);null 渲染 n/a 而非 "null" 字面量
        BacktestReport r = report();
        r.setTotalReturn(new BigDecimal("-0.0005"));
        r.setSharpeRatio(null);
        r.setProfitFactor(null);
        r.setWinRate(null);
        r.setMaxDrawdown(null);

        String text = ReportContextBuilder.build(r, List.of(), List.of());

        assertTrue(text.contains("totalReturn=-0.05%"), "负收益应带负号");
        assertTrue(text.contains("sharpeRatio=n/a"));
        assertTrue(text.contains("profitFactor=n/a (no losing trade)"), "profitFactor null 语义=无亏损单");
        assertTrue(text.contains("winRate=n/a"));
        assertTrue(text.contains("maxDrawdown=n/a"));
        assertFalse(text.contains("null,"), "不得出现裸 null 字面量");
    }

    @Test
    void build_nullIdentityFieldsRenderDash() {
        // name/symbol/timeframe/params 可空(导入路径),渲染 "-" 而非 null
        BacktestReport r = report();
        r.setName(null);
        r.setSymbol(null);
        r.setTimeframe(null);
        r.setParams(null);

        String text = ReportContextBuilder.build(r, List.of(), List.of());

        assertTrue(text.contains("name: -"));
        assertTrue(text.contains("symbol: -, timeframe: -"));
        assertTrue(text.contains("parameters: -"));
    }

    @Test
    void build_durationFormatsDaysHoursMinutes() {
        BacktestReport r = report();
        r.setAvgTradeDurationSeconds(93_725); // 1d 2h 2m 5s
        String text = ReportContextBuilder.build(r, List.of(), List.of());
        assertTrue(text.contains("avgTradeDuration=1d 2h 2m 5s"));

        r.setAvgTradeDurationSeconds(0);
        assertTrue(ReportContextBuilder.build(r, List.of(), List.of()).contains("avgTradeDuration=0s"));
    }

    @Test
    void build_curveDownsampledTo60PointsKeepingFirstAndLast() {
        // 200 点 → 均匀采样 ≤60,首末点必留(末点权益=最终资金,解读必需)
        BacktestReport r = report();
        List<EquityPoint> curve = new ArrayList<>();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 200; i++) {
            curve.add(new EquityPoint(t0.plusSeconds(i * 3600L), BigDecimal.valueOf(10_000 + i)));
        }

        String text = ReportContextBuilder.build(r, List.of(), curve);

        // 200 点 ceil 步长 4 → 索引 0,4,...,196(50 行)+ 补末点 199(1 行)= 51 行 ≤ 60 上限
        assertTrue(text.contains("equity curve: 200 points, sampled to 51"));
        assertTrue(text.contains(t0 + " 10000"), "首点必留");
        assertTrue(text.contains(t0.plusSeconds(199 * 3600L) + " 10199"), "末点必留(均匀步长漏末点时显式补齐)");
        long curveLines =
                text.lines().filter(l -> l.startsWith("  ") && l.contains("Z ")).count();
        assertEquals(51, curveLines, "采样行数 = 50 + 补末点 1");
    }

    @Test
    void build_smallCurveNotSampled() {
        // 点数 ≤60 不采样,逐点输出
        BacktestReport r = report();
        List<EquityPoint> curve = List.of(
                new EquityPoint(Instant.parse("2025-01-01T00:00:00Z"), new BigDecimal("10000")),
                new EquityPoint(Instant.parse("2025-01-02T00:00:00Z"), new BigDecimal("10100")));

        String text = ReportContextBuilder.build(r, List.of(), curve);

        assertTrue(text.contains("equity curve: 2 points, sampled to 2"));
        assertTrue(text.contains("10100"));
    }

    @Test
    void build_emptyCurveAndTrades_renderPlaceholders() {
        BacktestReport r = report();
        String text = ReportContextBuilder.build(r, List.of(), List.of());
        assertTrue(text.contains("equity curve: (empty)"));
        assertTrue(text.contains("trades: (none)"));
    }

    @Test
    void build_tradeAggregation_countsFeesBestWorstAndRecentWindow() {
        // 25 笔:聚合全量计数,明细只列最近 20 笔
        BacktestReport r = report();
        List<TradeRecord> trades = new ArrayList<>();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 25; i++) {
            TradeRecord t = new TradeRecord();
            t.setTime(t0.plusSeconds(i * 60L));
            t.setSide(i % 2 == 0 ? "buy" : "sell");
            t.setPrice(new BigDecimal("50000"));
            t.setAmount(new BigDecimal("0.1"));
            t.setFee(new BigDecimal("1"));
            // sell 腿带回合盈亏:第 1 笔 sell 亏 -10(最差),第 3 笔 sell 赚 +50(最好)
            if ("sell".equals(t.getSide())) {
                t.setRealizedPnl(i == 1 ? new BigDecimal("-10") : i == 3 ? new BigDecimal("50") : BigDecimal.ONE);
            } else {
                t.setRealizedPnl(new BigDecimal("-1"));
            }
            trades.add(t);
        }

        String text = ReportContextBuilder.build(r, trades, List.of());

        assertTrue(text.contains("25 records (13 buys / 12 sells)"), "买卖计数");
        assertTrue(text.contains("totalFee=25"), "总费用=全量累加");
        assertTrue(text.contains("bestClosePnl=50"));
        assertTrue(text.contains("worstClosePnl=-10"));
        assertTrue(text.contains("recent trades (latest 20 of 25)"));
        // 最近 20 笔 = 索引 5..24,第 0 笔(索引 0 的 t0)不在明细里
        long detailLines = text.lines()
                .filter(l -> l.startsWith("  ") && l.contains(" | "))
                .count();
        assertEquals(20, detailLines, "明细只列最近 20 笔");
    }

    @Test
    void build_paramsTruncatedWhenExceedingLimit() {
        BacktestReport r = report();
        r.setParams("p".repeat(ReportContextBuilder.MAX_PARAMS_CHARS + 100));

        String text = ReportContextBuilder.build(r, List.of(), List.of());

        assertTrue(text.contains("...(truncated)"), "超长参数应截断并标注");
        assertFalse(text.contains("p".repeat(ReportContextBuilder.MAX_PARAMS_CHARS + 1)), "截断后不保留全量");
    }

    @Test
    void build_overallContextTruncatedWhenExceedingLimit() {
        // 兜底闸:明细行靠超长 price 撑爆整体上限(曲线/明细已分别限流,此为最后防线)
        BacktestReport r = report();
        List<TradeRecord> trades = new ArrayList<>();
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        BigDecimal huge = new BigDecimal("1" + "0".repeat(2_500));
        for (int i = 0; i < ReportContextBuilder.MAX_RECENT_TRADES; i++) {
            TradeRecord t = new TradeRecord();
            t.setTime(t0.plusSeconds(i * 60L));
            t.setSide("buy");
            t.setPrice(huge);
            t.setAmount(BigDecimal.ONE);
            t.setFee(BigDecimal.ZERO);
            trades.add(t);
        }

        String text = ReportContextBuilder.build(r, trades, List.of());

        assertTrue(text.contains("... report context truncated (exceeds 20000 chars) ..."));
        assertTrue(text.length() <= ReportContextBuilder.MAX_CONTEXT_CHARS + 100, "截断后长度受控");
    }

    /** 基础报告 fixture:各用例按需覆写个别字段。 */
    private BacktestReport report() {
        BacktestReport r = new BacktestReport();
        r.setId(95L);
        r.setUserId(42L);
        r.setName("MA 双均线回测");
        r.setSymbol("BTC/USDT");
        r.setTimeframe("1h");
        r.setPeriodStart(Instant.parse("2025-01-01T00:00:00Z"));
        r.setPeriodEnd(Instant.parse("2025-06-01T00:00:00Z"));
        r.setParams("{\"initial_capital\":10000}");
        r.setTotalReturn(new BigDecimal("0.1234"));
        r.setSharpeRatio(new BigDecimal("1.23"));
        r.setMaxDrawdown(new BigDecimal("0.085"));
        r.setWinRate(new BigDecimal("0.55"));
        r.setProfitFactor(new BigDecimal("1.8"));
        r.setTotalTrades(120);
        r.setAvgTradeDurationSeconds(3600);
        return r;
    }
}
