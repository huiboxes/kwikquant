package com.kwikquant.ai.application;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.TradeRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * 回测报告 → system prompt 上下文文本(供 AI 回测解读使用)。
 *
 * <p>把一份已完成回测的报告组装成 LLM 可解读的结构化文本:配置(symbol/timeframe/区间/参数)+
 * 标准绩效指标 + 采样权益曲线 + 成交聚合与最近成交明细。纯文本组装,无 IO,可独立单测。
 *
 * <p><b>采样与截断</b>:权益曲线最多 10 万点、成交最多 10 万笔(ReportService 上限),直接注入会撑爆
 * 上下文窗口。曲线均匀降采样到 ≤{@value #MAX_CURVE_POINTS} 点(保留首末点,形状不失真);成交只聚合 +
 * 取最近 {@value #MAX_RECENT_TRADES} 笔明细;参数 JSON 截 {@value #MAX_PARAMS_CHARS} 字符;整体再套
 * {@value #MAX_CONTEXT_CHARS} 字符上限兜底(与 AiChatService 的 MAX_SOURCE_CHARS 同思路)。
 *
 * <p><b>指标口径</b>:totalReturn/winRate/maxDrawdown 为比率(0.1234=12.34%),此处换算成百分比呈现,
 * 避免 LLM 把 0.1234 误读成 0.12%。win/loss 笔数不在此重算——官方 winRate/profitFactor 由
 * PerformanceCalculator FIFO 配对算出,部分成交场景"按 sell 计数"与官方口径可能不一致,重算会引入
 * 自相矛盾的数字,只呈现官方指标。
 */
final class ReportContextBuilder {

    /** 权益曲线降采样上限(均匀取样,首末点必留)。 */
    static final int MAX_CURVE_POINTS = 60;

    /** 最近成交明细条数上限。 */
    static final int MAX_RECENT_TRADES = 20;

    /** 参数 JSON 注入上限(字符)。 */
    static final int MAX_PARAMS_CHARS = 2_000;

    /** 报告上下文整体上限(字符),兜底防曲线/成交超长。 */
    static final int MAX_CONTEXT_CHARS = 20_000;

    private ReportContextBuilder() {}

    /** 组装报告上下文文本。report 非空;trades/curve 可为空列表(降级为仅指标解读)。 */
    static String build(BacktestReport report, List<TradeRecord> trades, List<EquityPoint> curve) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Backtest report context (reportId=").append(report.getId()).append("):\n");
        sb.append("- name: ").append(safe(report.getName())).append('\n');
        sb.append("- symbol: ")
                .append(safe(report.getSymbol()))
                .append(", timeframe: ")
                .append(safe(report.getTimeframe()))
                .append('\n');
        sb.append("- period: ")
                .append(report.getPeriodStart())
                .append(" ~ ")
                .append(report.getPeriodEnd())
                .append('\n');
        sb.append("- parameters: ")
                .append(truncate(report.getParams(), MAX_PARAMS_CHARS))
                .append('\n');
        sb.append("- metrics: totalReturn=")
                .append(signedPercent(report.getTotalReturn()))
                .append(", sharpeRatio=")
                .append(num(report.getSharpeRatio()))
                .append(", maxDrawdown=")
                .append(percent(report.getMaxDrawdown()))
                .append(" (peak-to-trough)")
                .append(", winRate=")
                .append(percent(report.getWinRate()))
                .append(", profitFactor=")
                .append(report.getProfitFactor() != null ? num(report.getProfitFactor()) : "n/a (no losing trade)")
                .append(", totalTrades=")
                .append(report.getTotalTrades())
                .append(", avgTradeDuration=")
                .append(duration(report.getAvgTradeDurationSeconds()))
                .append('\n');
        appendCurve(sb, curve);
        appendTrades(sb, trades);
        sb.append('\n')
                .append("请基于以上回测数据做解读:1) 关键指标的含义与当前水平评估;2) 回撤与风险;3) 交易行为特征;"
                        + "4) 可执行的改进建议。只依据给定数据,不编造未给出的数字;最后提醒用户回测结果不代表未来收益。");
        String text = sb.toString();
        if (text.length() > MAX_CONTEXT_CHARS) {
            text = text.substring(0, MAX_CONTEXT_CHARS) + "\n... report context truncated (exceeds " + MAX_CONTEXT_CHARS
                    + " chars) ...";
        }
        return text;
    }

    /** 权益曲线均匀降采样到 ≤MAX_CURVE_POINTS(首末点必留),按 "time equity" 行呈现。 */
    private static void appendCurve(StringBuilder sb, List<EquityPoint> curve) {
        if (curve == null || curve.isEmpty()) {
            sb.append("- equity curve: (empty)\n");
            return;
        }
        int n = curve.size();
        // 向上取整步长保证采样行数 ≤ MAX_CURVE_POINTS(floor 会超限,如 200 点 floor 步长 3 → 67 行)
        int step = n <= MAX_CURVE_POINTS ? 1 : (n - 2) / (MAX_CURVE_POINTS - 1) + 1;
        boolean addLast = (n - 1) % step != 0;
        int sampled = (n - 1) / step + 1 + (addLast ? 1 : 0);
        sb.append("- equity curve: ")
                .append(n)
                .append(" points, sampled to ")
                .append(sampled)
                .append(":\n");
        for (int i = 0; i < n; i += step) {
            EquityPoint p = curve.get(i);
            sb.append("  ").append(p.time()).append(' ').append(num(p.equity())).append('\n');
        }
        // 均匀步长可能漏掉末点,显式补上(末点权益 = 最终资金,解读必需)
        if (addLast) {
            EquityPoint last = curve.get(n - 1);
            sb.append("  ")
                    .append(last.time())
                    .append(' ')
                    .append(num(last.equity()))
                    .append('\n');
        }
    }

    /** 成交聚合(buy/sell 计数、总费用、最大/最小平仓盈亏)+ 最近 MAX_RECENT_TRADES 笔明细。 */
    private static void appendTrades(StringBuilder sb, List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            sb.append("- trades: (none)\n");
            return;
        }
        long buys = 0;
        long sells = 0;
        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal best = null;
        BigDecimal worst = null;
        for (TradeRecord t : trades) {
            if ("buy".equalsIgnoreCase(t.getSide())) {
                buys++;
            } else if ("sell".equalsIgnoreCase(t.getSide())) {
                sells++;
            }
            if (t.getFee() != null) {
                totalFee = totalFee.add(t.getFee());
            }
            // realizedPnl:buy 腿 = -fee(开仓成本),sell 腿 = FIFO 配对的回合盈亏(enrichTrades 回填)
            if ("sell".equalsIgnoreCase(t.getSide()) && t.getRealizedPnl() != null) {
                if (best == null || t.getRealizedPnl().compareTo(best) > 0) {
                    best = t.getRealizedPnl();
                }
                if (worst == null || t.getRealizedPnl().compareTo(worst) < 0) {
                    worst = t.getRealizedPnl();
                }
            }
        }
        sb.append("- trades: ")
                .append(trades.size())
                .append(" records (")
                .append(buys)
                .append(" buys / ")
                .append(sells)
                .append(" sells), totalFee=")
                .append(num(totalFee))
                .append(", bestClosePnl=")
                .append(best != null ? num(best) : "n/a")
                .append(", worstClosePnl=")
                .append(worst != null ? num(worst) : "n/a")
                .append('\n');
        sb.append("- recent trades (latest ")
                .append(Math.min(trades.size(), MAX_RECENT_TRADES))
                .append(" of ")
                .append(trades.size())
                .append("): time | side | price | amount | fee | realizedPnl\n");
        int from = Math.max(0, trades.size() - MAX_RECENT_TRADES);
        for (int i = from; i < trades.size(); i++) {
            TradeRecord t = trades.get(i);
            sb.append("  ")
                    .append(t.getTime())
                    .append(" | ")
                    .append(t.getSide())
                    .append(" | ")
                    .append(num(t.getPrice()))
                    .append(" | ")
                    .append(num(t.getAmount()))
                    .append(" | ")
                    .append(num(t.getFee()))
                    .append(" | ")
                    .append(num(t.getRealizedPnl()))
                    .append('\n');
        }
    }

    /** 比率 → 带符号百分比文本(0.1234 → "+12.34%"),用于收益类指标;null → n/a。 */
    private static String signedPercent(BigDecimal ratio) {
        if (ratio == null) {
            return "n/a";
        }
        BigDecimal pct = ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    /** 比率 → 无符号百分比文本(0.55 → "55.00%"),用于胜率/回撤幅度等量级类指标;null → n/a。 */
    private static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "n/a";
        }
        return ratio.multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString() + "%";
    }

    /** 数值去尾零文本;null → n/a。 */
    private static String num(BigDecimal v) {
        return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
    }

    /** 秒 → 人类可读时长(1d 12h 30m / 45m 10s / 12s);0 → 0s。 */
    private static String duration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        Duration d = Duration.ofSeconds(seconds);
        long days = d.toDaysPart();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long secs = d.toSecondsPart();
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        if (days > 0 || hours > 0 || minutes > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(secs).append("s");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() > maxChars ? s.substring(0, maxChars) + "...(truncated)" : s;
    }
}
