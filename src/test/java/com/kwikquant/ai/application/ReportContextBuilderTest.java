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

    // ---- PERP 感知(marketType/强平模型声明 + positionEffect 聚合) ----

    private TradeRecord perpTrade(
            String effect, String price, String amount, String fee, BigDecimal realizedPnl, boolean liquidation) {
        TradeRecord t = new TradeRecord();
        t.setTime(Instant.parse("2025-03-01T12:00:00Z"));
        // side 是派生量:OPEN_LONG/CLOSE_SHORT → buy,其余 → sell
        t.setSide(("OPEN_LONG".equals(effect) || "CLOSE_SHORT".equals(effect)) ? "buy" : "sell");
        t.setPositionEffect(effect);
        t.setPrice(new BigDecimal(price));
        t.setAmount(new BigDecimal(amount));
        t.setFee(new BigDecimal(fee));
        t.setRealizedPnl(realizedPnl);
        t.setLiquidation(liquidation);
        return t;
    }

    @Test
    void build_perpReport_declaresModelAndAggregatesByEffect() {
        BacktestReport r = report();
        r.setSymbol("BTC/USDT:USDT");
        r.setMarketType("PERP");
        r.setLiquidationModel("BAR_EXTREME_APPROX");

        List<TradeRecord> trades = List.of(
                perpTrade("OPEN_LONG", "40000", "1", "8", new BigDecimal("-8"), false),
                // 穿零反转行:effect 是 OPEN_SHORT(计入 opens),但 realizedPnl 是平仓段盈亏
                perpTrade("OPEN_SHORT", "43000", "2", "17.2", new BigDecimal("2982.8"), false),
                perpTrade("CLOSE_SHORT", "42000", "1", "8.4", new BigDecimal("983"), true));

        String text = ReportContextBuilder.build(r, trades, List.of());

        assertTrue(text.contains("marketType: PERP"), "PERP 声明行在场");
        assertTrue(text.contains("BAR_EXTREME_APPROX"), "强平近似模型必须声明");
        assertTrue(
                text.contains("2 opens / 1 closes (incl. 1 liquidation rows, counted in closes)"),
                "按 effect 聚合而非 buy/sell,强平包含关系显式声明");
        assertFalse(text.contains("buys /"), "SPOT 聚合文案不得出现");
        // best/worst 与 enrichTrades 平仓腿口径一致:穿零反转行(effect=OPEN_SHORT)的 realizedPnl
        // 承载平仓段盈亏(2982.8 ≠ -fee),计入 best;CLOSE_SHORT 983 是 worst
        assertTrue(text.contains("bestClosePnl=2982.8"));
        assertTrue(text.contains("worstClosePnl=983"));
        assertTrue(text.contains("time | positionEffect | price | amount | fee | realizedPnl (* = liquidation)"));
        assertTrue(text.contains("OPEN_SHORT | 43000"), "明细行 effect 列取代 side 列");
        assertTrue(text.contains("CLOSE_SHORT* | 42000"), "强平行带 * 标记");
    }

    @Test
    void build_spotReport_noMarketTypeLineAndAggregationUnchanged() {
        BacktestReport r = report();
        r.setMarketType("SPOT");

        TradeRecord buy = new TradeRecord();
        buy.setTime(Instant.parse("2025-03-01T12:00:00Z"));
        buy.setSide("buy");
        buy.setPrice(new BigDecimal("100"));
        buy.setAmount(new BigDecimal("10"));
        buy.setFee(BigDecimal.ONE);
        buy.setRealizedPnl(new BigDecimal("-1"));
        TradeRecord sell = new TradeRecord();
        sell.setTime(Instant.parse("2025-03-02T12:00:00Z"));
        sell.setSide("sell");
        sell.setPrice(new BigDecimal("110"));
        sell.setAmount(new BigDecimal("10"));
        sell.setFee(BigDecimal.ONE);
        sell.setRealizedPnl(new BigDecimal("99"));

        String text = ReportContextBuilder.build(r, List.of(buy, sell), List.of());

        assertFalse(text.contains("marketType:"), "SPOT 报告不注入声明行(输出与存量一致)");
        assertTrue(text.contains("1 buys / 1 sells"));
        assertTrue(text.contains("time | side | price | amount | fee | realizedPnl"));
        assertTrue(text.contains("bestClosePnl=99"));
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

    // ---- warnings 独立注入(P1:params 截断不得吞掉风险披露) ----

    @Test
    void build_perpReport_warningsSurviveParamsTruncation() {
        BacktestReport r = report();
        r.setSymbol("BTC/USDT:USDT");
        r.setMarketType("PERP");
        r.setLiquidationModel("BAR_EXTREME_APPROX");
        // 用户参数把 params 撑过 MAX_PARAMS_CHARS,warnings 在 JSON 末尾(截断保头砍尾必丢)
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            padding.append("0123456789abcdef");
        }
        r.setParams("{\"longParam\": \"" + padding + "\", \"_kwikquant\": {\"bars\": 100, \"warnings\": ["
                + "\"资金费跨所代理：序列含 12 期 PROXY_BINANCE 代理费率（存在跨所基差；持仓跨越这些期次时按代理值结算，成本与本所真值有偏差）\","
                + "\"强平于 2025-03-01T12:00:00Z：LONG 0.5 @ 38000（ISOLATED）\"]}}");

        String text = ReportContextBuilder.build(r, List.of(), List.of());

        assertTrue(text.contains("- dataQualityWarnings: "), "warnings 独立注入段在场");
        assertTrue(text.contains("PROXY_BINANCE"), "跨所代理披露不被 params 截断吞掉");
        assertTrue(text.contains("强平于 2025-03-01T12:00:00Z"), "强平事件披露在场");
        assertTrue(text.contains("必须向用户说明该部分资金费取自"), "PERP 指令段带代理披露义务");
        // parameters 行仍按旧预算截断(warnings 不占它的预算,也不因它丢失)
        assertTrue(text.contains("- parameters: "), "parameters 行保留");
    }

    @Test
    void build_oversizedContext_instructionsSurviveTruncation() {
        // 指令/免责段必须在截断保护区之外:旧实现指令拼在 body 尾部,超 20000 时恰是最不能丢的
        // 免责声明先被砍——把 build 的截断算术改回"整体截断"此用例必须红(超长明细撑爆口径
        // 与 build_overallContextTruncatedWhenExceedingLimit 同源)
        BacktestReport r = report();
        r.setMarketType("PERP");
        r.setLiquidationModel("BAR_EXTREME_APPROX");
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

        assertTrue(text.contains("... report context truncated"), "超限有截断标记");
        assertTrue(text.contains("回测结果不代表未来收益"), "免责指令存活(截断保护区外)");
        assertTrue(text.contains("必须向用户说明该部分资金费取自"), "PERP 披露义务指令在截断后仍在场");
        assertTrue(text.length() <= ReportContextBuilder.MAX_CONTEXT_CHARS + 400, "总长受控(保护区后缀有界)");
    }

    @Test
    void build_brokenParams_noWarningsSectionButStillBuilds() {
        BacktestReport r = report();
        r.setParams("{bad json");
        String text = ReportContextBuilder.build(r, List.of(), List.of());
        assertFalse(text.contains("- dataQualityWarnings: "));
        assertTrue(text.contains("- metrics: "), "损坏 params 降级不阻断上下文");
    }

    // ---- PERP 曲线扩展列 + 口径声明 ----

    @Test
    void build_perpReport_curveCarriesMarginAndFundingColumns() {
        BacktestReport r = report();
        r.setMarketType("PERP");
        r.setLiquidationModel("BAR_EXTREME_APPROX");
        List<EquityPoint> curve = List.of(
                new EquityPoint(
                        Instant.parse("2025-01-01T00:00:00Z"),
                        new BigDecimal("10000"),
                        new BigDecimal("420"),
                        new BigDecimal("-1.5")),
                new EquityPoint(
                        Instant.parse("2025-01-02T00:00:00Z"),
                        new BigDecimal("10100"),
                        new BigDecimal("0"),
                        new BigDecimal("2.25")));

        String text = ReportContextBuilder.build(r, List.of(), curve);

        assertTrue(text.contains("(columns: time equity marginUsed fundingCum)"), "PERP 曲线列头声明");
        assertTrue(text.contains("10000 420 -1.5"), "marginUsed/fundingCum 不再被丢弃");
        assertTrue(text.contains("10100 0 2.25"));
        // 口径声明双向:毛配对 + 权益曲线已含(防 LLM 把资金费归属说反)
        assertTrue(text.contains("totalReturn/maxDrawdown/sharpeRatio 基于权益曲线,已含资金费与未实现盈亏"));
        assertTrue(text.contains("bar 极值近似强平(BAR_EXTREME_APPROX)"), "模型释义带枚举名");
    }

    @Test
    void build_spotReport_curveStaysTwoColumns() {
        BacktestReport r = report();
        r.setMarketType("SPOT");
        List<EquityPoint> curve =
                List.of(new EquityPoint(Instant.parse("2025-01-01T00:00:00Z"), new BigDecimal("10000")));
        String text = ReportContextBuilder.build(r, List.of(), curve);
        assertFalse(text.contains("marginUsed"), "SPOT 曲线输出不变");
        assertFalse(text.contains("- dataQualityWarnings: "), "无 warnings 不渲染空段");
    }

    @Test
    void build_unknownLiquidationModel_outputsNameOnly() {
        BacktestReport r = report();
        r.setMarketType("PERP");
        r.setLiquidationModel("FUTURE_MODEL_X");
        String text = ReportContextBuilder.build(r, List.of(), List.of());
        assertTrue(text.contains("liquidation model: FUTURE_MODEL_X;"), "未知枚举只输名字不硬拼释义");
        assertFalse(text.contains("FUTURE_MODEL_X = bar"), "不得给未知模型拼错误释义");
    }
}
