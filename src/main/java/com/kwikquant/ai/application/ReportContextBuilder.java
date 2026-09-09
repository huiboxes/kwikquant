package com.kwikquant.ai.application;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.TradeRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    /** warnings 独立注入段上限(字符)——不参与 parameters 行截断,风险披露不与用户参数抢预算。 */
    static final int MAX_WARNINGS_CHARS = 2_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        boolean perp = "PERP".equals(report.getMarketType());
        if (perp) {
            // PERP 报告必须声明近似模型与指标口径,防 LLM 按 SPOT 语义误读(perp-backtest-spec §8.1)。
            // 口径声明必须双向:只说"winRate 不含资金费"不说"totalReturn 已含",LLM 会推断
            // "该回测未计资金费成本、实盘更差"——事实相反(资金费/未实现在权益曲线里)
            sb.append("- marketType: PERP (永续合约净持仓回测; liquidation model: ")
                    .append(liquidationModelDesc(report.getLiquidationModel()))
                    .append(";winRate/profitFactor 为毛配对口径,不含资金费与未实现盈亏;"
                            + "totalReturn/maxDrawdown/sharpeRatio 基于权益曲线,已含资金费与未实现盈亏;"
                            + "逐笔 side 是派生量(buy≠开仓),方向语义看 positionEffect)\n");
        }
        // warnings 独立注入(不参与下行 parameters 截断):warnings 是 params JSON 的最后一个键,
        // 而截断保头砍尾——拒单/强平/资金费代理披露恰是超长 params 下最先丢失的内容
        // (perp-backtest-spec §8.1 承诺 AI 链路消费 warnings,截断链路上该承诺必然落空)
        String warnings = extractWarnings(report.getParams());
        if (warnings != null) {
            sb.append("- dataQualityWarnings: ").append(warnings).append('\n');
        }
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
                .append(" (paired rounds, may differ from trade row count)")
                .append(", avgTradeDuration=")
                .append(duration(report.getAvgTradeDurationSeconds()))
                .append('\n');
        appendCurve(sb, curve, perp);
        appendTrades(sb, trades, perp);
        String instructions = "请基于以上回测数据做解读:1) 关键指标的含义与当前水平评估;2) 回撤与风险;3) 交易行为特征;"
                + "4) 可执行的改进建议。只依据给定数据,不编造未给出的数字;最后提醒用户回测结果不代表未来收益。"
                + (perp
                        ? "若 dataQualityWarnings 含资金费跨所代理(PROXY_BINANCE),必须向用户说明该部分资金费取自"
                                + " Binance 同期次代理值、存在跨所基差;强平为 bar 极值近似,可能高估强平频率(保守偏差)。"
                        : "");
        // 指令段在截断保护区之外:整体超限时砍 body 尾段(数据区),头部声明与尾部指令必留
        // (旧实现指令拼在尾部,超限即被砍——免责声明恰是最不能丢的一行)
        String body = sb.toString();
        String suffix = "\n" + instructions;
        if (body.length() + suffix.length() > MAX_CONTEXT_CHARS) {
            int keep = Math.max(0, MAX_CONTEXT_CHARS - suffix.length() - 80);
            body = body.substring(0, keep) + "\n... report context truncated (exceeds " + MAX_CONTEXT_CHARS
                    + " chars) ...";
        }
        return body + suffix;
    }

    /** 强平模型枚举 → 释义(switch 单点;未知新枚举只输名字,不硬拼错释义)。 */
    private static String liquidationModelDesc(String model) {
        if (model == null) {
            return "未声明(数据异常)";
        }
        return switch (model) {
            case "BAR_EXTREME_APPROX" -> "bar 极值近似强平(BAR_EXTREME_APPROX),存在保守偏差,失真清单见 perp-backtest-spec §4.2";
            default -> model;
        };
    }

    /**
     * 从 params JSON 提取 {@code _kwikquant.warnings} 拼为独立注入段。解析失败/无 warnings
     * 返 null(降级为仅 parameters 截断行,不阻断上下文组装)。
     */
    private static String extractWarnings(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode warnings = MAPPER.readTree(paramsJson).path("_kwikquant").path("warnings");
            if (!warnings.isArray() || warnings.size() == 0) {
                return null;
            }
            StringBuilder joined = new StringBuilder();
            for (JsonNode w : warnings) {
                if (joined.length() > 0) {
                    joined.append(" | ");
                }
                joined.append(w.asText());
                if (joined.length() >= MAX_WARNINGS_CHARS) {
                    break;
                }
            }
            return truncate(joined.toString(), MAX_WARNINGS_CHARS);
        } catch (RuntimeException e) { // noqa: 损坏 params 降级,不阻断解读(指标行仍完整)
            return null;
        }
    }

    /** 权益曲线均匀降采样到 ≤MAX_CURVE_POINTS(首末点必留)。SPOT 按 "time equity" 行;
     * PERP 追加 marginUsed/fundingCum 两列(DTO 已有值,旧实现丢弃——LLM 无法核对资金费累计影响)。 */
    private static void appendCurve(StringBuilder sb, List<EquityPoint> curve, boolean perp) {
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
                .append(perp ? " (columns: time equity marginUsed fundingCum)" : "")
                .append(":\n");
        for (int i = 0; i < n; i += step) {
            appendCurvePoint(sb, curve.get(i), perp);
        }
        // 均匀步长可能漏掉末点,显式补上(末点权益 = 最终资金,解读必需)
        if (addLast) {
            appendCurvePoint(sb, curve.get(n - 1), perp);
        }
    }

    private static void appendCurvePoint(StringBuilder sb, EquityPoint p, boolean perp) {
        sb.append("  ").append(p.time()).append(' ').append(num(p.equity()));
        if (perp) {
            sb.append(' ')
                    .append(p.marginUsed() != null ? num(p.marginUsed()) : "-")
                    .append(' ')
                    .append(p.fundingCum() != null ? num(p.fundingCum()) : "-");
        }
        sb.append('\n');
    }

    /**
     * 成交聚合 + 最近 MAX_RECENT_TRADES 笔明细。SPOT 按 buy/sell 计数;PERP 按 positionEffect
     * 聚合(开/平/强平计数)——PERP 的 side 是派生量,buy≠开仓,按 side 聚合会系统性误导 LLM。
     */
    private static void appendTrades(StringBuilder sb, List<TradeRecord> trades, boolean perp) {
        if (trades == null || trades.isEmpty()) {
            sb.append("- trades: (none)\n");
            return;
        }
        long buys = 0;
        long sells = 0;
        long opens = 0;
        long closes = 0;
        long liquidations = 0;
        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal best = null;
        BigDecimal worst = null;
        for (TradeRecord t : trades) {
            if (perp) {
                String effect = t.getPositionEffect();
                if (effect != null && effect.startsWith("OPEN")) {
                    opens++;
                } else if (effect != null) {
                    closes++;
                }
                if (t.isLiquidation()) {
                    liquidations++;
                }
            } else if ("buy".equalsIgnoreCase(t.getSide())) {
                buys++;
            } else if ("sell".equalsIgnoreCase(t.getSide())) {
                sells++;
            }
            if (t.getFee() != null) {
                totalFee = totalFee.add(t.getFee());
            }
            // realizedPnl:开仓腿 = -fee(开仓成本),平仓腿 = 配对回合盈亏(enrichTrades 回填)。
            // 平仓腿判定:SPOT = sell 行;PERP = CLOSE_* 行(CLOSE_SHORT 的 side=buy,不能按 side 判)
            // + 穿零反转行——effect 记 OPEN_*(用户视角一条)但 realizedPnl 承载平仓段配对盈亏
            // (PerformanceCalculator closePnlMap 口径);纯开仓腿 realizedPnl 恒等于 -fee,以此区分。
            // 极端巧合(反转行 closeDelta 恰等于 -fee)会漏计一行,展示级统计可接受。
            boolean closeLeg;
            if (perp) {
                String effect = t.getPositionEffect();
                if (effect != null && effect.startsWith("CLOSE")) {
                    closeLeg = true;
                } else if (effect != null && t.getRealizedPnl() != null) {
                    BigDecimal feeOfT = t.getFee() != null ? t.getFee() : BigDecimal.ZERO;
                    closeLeg = t.getRealizedPnl().compareTo(feeOfT.negate()) != 0;
                } else {
                    closeLeg = false;
                }
            } else {
                closeLeg = "sell".equalsIgnoreCase(t.getSide());
            }
            if (closeLeg && t.getRealizedPnl() != null) {
                if (best == null || t.getRealizedPnl().compareTo(best) > 0) {
                    best = t.getRealizedPnl();
                }
                if (worst == null || t.getRealizedPnl().compareTo(worst) < 0) {
                    worst = t.getRealizedPnl();
                }
            }
        }
        sb.append("- trades: ").append(trades.size()).append(" records (");
        if (perp) {
            // 强平行必为 CLOSE_*:同一行既进 closes 又是 liquidation,并置三个计数会被读成
            // 互斥分类(2+1+1=4 行?实际 3 行)——显式声明包含关系
            sb.append(opens)
                    .append(" opens / ")
                    .append(closes)
                    .append(" closes (incl. ")
                    .append(liquidations)
                    .append(" liquidation rows, counted in closes)");
        } else {
            sb.append(buys).append(" buys / ").append(sells).append(" sells");
        }
        sb.append("), totalFee=")
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
                .append("): ")
                .append(
                        perp
                                ? "time | positionEffect | price | amount | fee | realizedPnl (* = liquidation)"
                                : "time | side | price | amount | fee | realizedPnl")
                .append('\n');
        int from = Math.max(0, trades.size() - MAX_RECENT_TRADES);
        for (int i = from; i < trades.size(); i++) {
            TradeRecord t = trades.get(i);
            sb.append("  ")
                    .append(t.getTime())
                    .append(" | ")
                    .append(perp ? safe(t.getPositionEffect()) : t.getSide())
                    .append(t.isLiquidation() ? "*" : "")
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
