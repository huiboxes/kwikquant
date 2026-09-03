package com.kwikquant.report.application;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.PerformanceCalculator;
import com.kwikquant.report.domain.PerformanceMetrics;
import com.kwikquant.report.domain.PositionSnapshot;
import com.kwikquant.report.domain.ReportExportFailedException;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.report.domain.TradeRecord;
import com.kwikquant.report.infrastructure.BacktestReportMapper;
import com.kwikquant.report.infrastructure.TradeRecordMapper;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 单份报告最大交易记录数，也被 {@code BacktestSubmitRequest} 的 {@code @Size} 校验引用。 */
    public static final int MAX_TRADES = 100_000;

    /** 单份报告最大权益曲线点数，也被 {@code BacktestSubmitRequest} 的 {@code @Size} 校验引用。 */
    public static final int MAX_EQUITY_POINTS = 100_000;

    private static final int TRADE_INSERT_BATCH_SIZE = 1_000;

    private static final String SOURCE_PLATFORM = "PLATFORM";
    private static final String SOURCE_IMPORT = "IMPORT";

    private final BacktestReportMapper reportMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final ObjectMapper objectMapper;

    @Value("${kwikquant.report.risk-free-rate:0.02}")
    private BigDecimal riskFreeRate;

    public ReportService(
            BacktestReportMapper reportMapper, TradeRecordMapper tradeRecordMapper, ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BacktestReport submit(
            long userId,
            String name,
            Object params,
            String symbol,
            String timeframe,
            java.time.Instant periodStart,
            java.time.Instant periodEnd,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve) {
        return doSubmit(
                userId,
                name,
                params,
                symbol,
                null,
                null,
                timeframe,
                periodStart,
                periodEnd,
                trades,
                equityCurve,
                SOURCE_PLATFORM);
    }

    @Transactional
    public BacktestReport importResult(
            long userId,
            String name,
            Object params,
            String symbol,
            String timeframe,
            java.time.Instant periodStart,
            java.time.Instant periodEnd,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve) {
        return doSubmit(
                userId,
                name,
                params,
                symbol,
                null,
                null,
                timeframe,
                periodStart,
                periodEnd,
                trades,
                equityCurve,
                SOURCE_IMPORT);
    }

    private BacktestReport doSubmit(
            long userId,
            String name,
            Object params,
            String symbol,
            String symbolsJson,
            String finalPositionsJson,
            String timeframe,
            java.time.Instant periodStart,
            java.time.Instant periodEnd,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve,
            String source) {

        // --- validation ---
        if (trades == null) {
            throw new ReportInvalidPayloadException("trades must not be null");
        }
        if (trades.size() > MAX_TRADES) {
            throw new ReportInvalidPayloadException("trades exceed max " + MAX_TRADES);
        }
        if (equityCurve != null && equityCurve.size() > MAX_EQUITY_POINTS) {
            throw new ReportInvalidPayloadException("equity curve exceeds max " + MAX_EQUITY_POINTS + " points");
        }
        if (periodStart == null || periodEnd == null) {
            throw new ReportInvalidPayloadException("period start and end must not be null");
        }
        if (!periodStart.isBefore(periodEnd)) {
            throw new ReportInvalidPayloadException("period start must be before end");
        }
        for (TradeRecord trade : trades) {
            if (trade.getTime() == null) {
                throw new ReportInvalidPayloadException("trade time must not be null");
            }
            if (trade.getPrice() == null || trade.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ReportInvalidPayloadException("trade price must be > 0");
            }
            if (trade.getAmount() == null || trade.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ReportInvalidPayloadException("trade amount must be > 0");
            }
        }

        // --- serialize params + equity curve ---
        String paramsJson = serializeToJson(params);
        String equityCurveJson = equityCurve != null ? serializeToJson(equityCurve) : null;

        // --- insert report ---
        BacktestReport report = new BacktestReport();
        report.setUserId(userId);
        report.setName(name);
        report.setParams(paramsJson);
        report.setSymbol(symbol);
        report.setSymbols(symbolsJson);
        report.setFinalPositions(finalPositionsJson);
        report.setTimeframe(timeframe);
        report.setPeriodStart(periodStart);
        report.setPeriodEnd(periodEnd);
        report.setEquityCurve(equityCurveJson);
        report.setSource(source);
        reportMapper.insert(report);

        // --- insert trades ---
        for (TradeRecord trade : trades) {
            trade.setReportId(report.getId());
        }
        // P1-2: 用 equityCurve 首点的真实初始资金回填 trades[].equity，避免单参数 enrichTrades
        // 用首笔买入名义额估算导致与权益曲线口径不一致（100,000 vs ~1102）。
        BigDecimal initialCapital = equityCurve != null && !equityCurve.isEmpty()
                ? equityCurve.getFirst().equity()
                : null;
        PerformanceCalculator.enrichTrades(trades, initialCapital);
        for (int start = 0; start < trades.size(); start += TRADE_INSERT_BATCH_SIZE) {
            int end = Math.min(start + TRADE_INSERT_BATCH_SIZE, trades.size());
            tradeRecordMapper.batchInsert(trades.subList(start, end));
        }

        // --- calculate metrics ---
        PerformanceMetrics metrics = PerformanceCalculator.calculate(trades, equityCurve, riskFreeRate);
        report.setTotalReturn(metrics.totalReturn());
        report.setSharpeRatio(metrics.sharpeRatio());
        report.setMaxDrawdown(metrics.maxDrawdown());
        report.setWinRate(metrics.winRate());
        report.setProfitFactor(metrics.profitFactor());
        report.setTotalTrades(metrics.totalTrades());
        report.setAvgTradeDurationSeconds(metrics.avgTradeDurationSeconds());
        reportMapper.updateMetrics(report);

        log.info("[report] created report id={} source={} trades={}", report.getId(), source, trades.size());
        return report;
    }

    /**
     * 从回测结果 JSON 提交回测报告。解析 trades/equity_curve/period/meta → {@link #doSubmit} source=PLATFORM。
     *
     * <p>组合(多标的)回测结果额外携带 {@code symbols}(标的列表)、逐笔成交的 {@code symbol}
     * 与终仓 {@code positions};解析后分别落 {@code backtest_reports.symbols}、
     * {@code trade_records.symbol}、{@code backtest_reports.final_positions}。
     *
     * <p>report 拥有结果 JSON 解析(TradeRecord/EquityPoint 是 report/domain),避免 strategy(BacktestExecutionGateway)
     * 直接依赖 report::domain,只需 report::application。
     */
    @Transactional
    public long submitBacktestResult(long userId, String section8Json) {
        if (section8Json == null || section8Json.isBlank()) {
            throw new ReportInvalidPayloadException("section8 json is empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(section8Json);
        } catch (JacksonException e) {
            throw new ReportInvalidPayloadException("invalid backtest result json: " + e.getMessage());
        }
        String name = root.path("name").asText("backtest");
        JsonNode paramsNode = root.path("params");
        Object params = paramsNode.isMissingNode() ? Map.of() : paramsNode;
        // 组合回测:优先取结构化标的列表;symbol 列存逗号拼接(展示/列表过滤),单标的沿用原字段
        List<String> symbols = parseSymbols(root.path("symbols"));
        String symbol = symbols.isEmpty() ? root.path("symbol").asText("") : String.join(",", symbols);
        String symbolsJson = symbols.isEmpty() ? null : serializeToJson(symbols);
        String timeframe = root.path("timeframe").asText("");
        Instant periodStart = parsePeriod(root.path("period").path("start").asText(null));
        Instant periodEnd = parsePeriod(root.path("period").path("end").asText(null));
        List<TradeRecord> trades = parseTrades(root.path("trades"));
        List<EquityPoint> equityCurve = parseEquityCurve(root.path("equity_curve"));
        String finalPositionsJson = parseFinalPositions(root.path("positions"));
        // 返 reportId（long），让 BacktestExecutionGateway 回填 task.report_id 而不依赖 report::domain
        return doSubmit(
                        userId,
                        name,
                        params,
                        symbol,
                        symbolsJson,
                        finalPositionsJson,
                        timeframe,
                        periodStart,
                        periodEnd,
                        trades,
                        equityCurve,
                        SOURCE_PLATFORM)
                .getId();
    }

    /** 解析组合标的列表(缺失/非数组返空 = 单标的报告)。 */
    private List<String> parseSymbols(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>();
        for (JsonNode s : node) {
            String v = s.asText("");
            if (!v.isBlank()) {
                symbols.add(v);
            }
        }
        return symbols;
    }

    /**
     * 解析组合终仓快照。兼容两种形态:{@code {symbol: {qty, avg_price}}} 对象(worker 输出)与
     * {@code [{symbol, qty, avg_price}]} 数组。归一化为 {@link PositionSnapshot} 列表的 JSON
     * (camelCase avgPrice);无可解析内容返 null(单标的报告不落该列)。
     */
    private String parseFinalPositions(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        List<PositionSnapshot> positions = new ArrayList<>();
        if (node.isObject() && node.isEmpty()) {
            return null;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                positions.add(toPositionSnapshot(e.getKey(), e.getValue()));
            }
        } else if (node.isArray()) {
            for (JsonNode p : node) {
                positions.add(toPositionSnapshot(p.path("symbol").asText(""), p));
            }
        } else {
            throw new ReportInvalidPayloadException("positions must be an object or array");
        }
        return serializeToJson(positions);
    }

    private PositionSnapshot toPositionSnapshot(String symbol, JsonNode value) {
        BigDecimal qty = new BigDecimal(value.path("qty").asText("0"));
        BigDecimal avgPrice = new BigDecimal(
                value.has("avg_price")
                        ? value.path("avg_price").asText("0")
                        : value.path("avgPrice").asText("0"));
        return new PositionSnapshot(symbol, qty, avgPrice);
    }

    /**
     * 导出报告为 import 消费格式(与 {@code BacktestSubmitRequest} JSON 结构一致)。
     * 归属校验同 {@link #getById};params 存储为 JSON 字符串,导出时解析回对象
     * (import 要求 params 为对象);解析失败 → {@link ReportExportFailedException}(9004)。
     */
    public ReportExportView exportForImport(long reportId, long userId) {
        BacktestReport report = getById(reportId, userId);
        // 组合(多标的)报告的导出契约尚未承载 symbols/逐笔 symbol/positions,导出会丢失标的维度、
        // 且逗号拼接的 symbol 违反 import 端正则 → 再导入必失败或跨标的错配。导入导出闭环未支持前,
        // 显式拒绝而非静默产出不可回灌的文件。
        if (report.getSymbols() != null && !report.getSymbols().isBlank()) {
            throw new ReportExportFailedException("portfolio backtest report export is not supported yet");
        }
        List<TradeRecord> trades = getTradeRecords(reportId, userId);
        List<EquityPoint> equity = parseEquityCurveForExport(report.getEquityCurve());

        Map<String, Object> params = Map.of();
        if (report.getParams() != null && !report.getParams().isBlank()) {
            try {
                params = objectMapper.readValue(report.getParams(), new TypeReference<Map<String, Object>>() {});
            } catch (JacksonException e) {
                throw new ReportExportFailedException("report params malformed: " + e.getMessage(), e);
            }
        }

        return new ReportExportView(
                report.getName(),
                params,
                report.getSymbol(),
                report.getTimeframe(),
                new ReportExportView.PeriodRange(report.getPeriodStart(), report.getPeriodEnd()),
                trades.stream()
                        .map(t -> new ReportExportView.TradeEntry(
                                t.getTime(), t.getSide(), t.getPrice(), t.getAmount(), t.getFee()))
                        .toList(),
                equity.stream()
                        .map(e -> new ReportExportView.EquityPointEntry(e.time(), e.equity()))
                        .toList());
    }

    private List<TradeRecord> parseTrades(JsonNode tradesNode) {
        List<TradeRecord> trades = new ArrayList<>();
        if (tradesNode == null || !tradesNode.isArray()) return trades;
        for (JsonNode t : tradesNode) {
            TradeRecord tr = new TradeRecord();
            tr.setTime(parsePeriod(t.path("time").asText(null)));
            // 组合回测逐笔成交带标的;单标的报告缺省该键(标的由报告 symbol 隐含)
            tr.setSymbol(t.path("symbol").asText(null));
            tr.setSide(t.path("side").asText(PerformanceCalculator.SIDE_BUY));
            tr.setPrice(new BigDecimal(t.path("price").asText("0")));
            tr.setAmount(new BigDecimal(t.path("amount").asText("0")));
            tr.setFee(new BigDecimal(t.path("fee").asText("0")));
            trades.add(tr);
        }
        return trades;
    }

    private List<EquityPoint> parseEquityCurve(JsonNode eqNode) {
        List<EquityPoint> points = new ArrayList<>();
        if (eqNode == null || !eqNode.isArray()) return points;
        for (JsonNode e : eqNode) {
            points.add(new EquityPoint(
                    parsePeriod(e.path("time").asText(null)),
                    new BigDecimal(e.path("equity").asText("0"))));
        }
        return points;
    }

    private Instant parsePeriod(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    public PageDto<BacktestReport> listByUser(long userId, String symbol, PageQuery pq) {
        List<BacktestReport> items = reportMapper.findByUserId(userId, symbol, pq.pageSize(), pq.offset());
        long total = reportMapper.countByUserId(userId, symbol);
        return PageDto.of(items, pq.page(), pq.pageSize(), total);
    }

    public BacktestReport getById(long id, long userId) {
        BacktestReport report = reportMapper.findById(id);
        if (report == null || report.getUserId() != userId) {
            throw new ReportNotFoundException("report not found: " + id);
        }
        return report;
    }

    public List<TradeRecord> getTradeRecords(long reportId, long userId) {
        // verify ownership
        getById(reportId, userId);
        return tradeRecordMapper.findByReportId(reportId);
    }

    /** 宽松解析(读路径):分标的终仓快照解析失败仅 warn 返空,不打断详情渲染。 */
    public List<PositionSnapshot> parsePositions(String finalPositionsJson) {
        if (finalPositionsJson == null || finalPositionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(finalPositionsJson, new TypeReference<List<PositionSnapshot>>() {});
        } catch (JacksonException e) {
            log.warn("[report] failed to parse final positions: {}", e.getMessage());
            return List.of();
        }
    }

    /** 宽松解析(读路径):组合标的列表解析失败仅 warn 返空,不打断详情渲染。 */
    public List<String> parseSymbols(String symbolsJson) {
        if (symbolsJson == null || symbolsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(symbolsJson, new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            log.warn("[report] failed to parse symbols: {}", e.getMessage());
            return List.of();
        }
    }

    /** 宽松解析(读路径):解析失败仅 warn 返空,不打断图表渲染。 */
    public List<EquityPoint> parseEquityCurve(String equityCurveJson) {
        try {
            return readEquityCurve(equityCurveJson);
        } catch (JacksonException e) {
            log.warn("[report] failed to parse equity curve: {}", e.getMessage());
            return List.of();
        }
    }

    /** 严格解析(导出路径):存储数据损坏 → 9004 REPORT_EXPORT_FAILED,不静默吞掉。 */
    private List<EquityPoint> parseEquityCurveForExport(String equityCurveJson) {
        try {
            return readEquityCurve(equityCurveJson);
        } catch (JacksonException e) {
            throw new ReportExportFailedException("report equity curve malformed", e);
        }
    }

    private List<EquityPoint> readEquityCurve(String equityCurveJson) {
        if (equityCurveJson == null || equityCurveJson.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(equityCurveJson, new TypeReference<List<EquityPoint>>() {});
    }

    /** 批量取 reportId→totalReturn 映射，返 Map 不返 domain（保模块边界，供 strategy 调用）。空 ids 返空 Map 不查 DB。 */
    public Map<Long, BigDecimal> findTotalReturnsByIds(List<Long> reportIds, long userId) {
        if (reportIds == null || reportIds.isEmpty()) {
            return Map.of();
        }
        return reportMapper.findByIds(reportIds, userId).stream()
                .collect(Collectors.toMap(BacktestReport::getId, BacktestReport::getTotalReturn));
    }

    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            throw new ReportInvalidPayloadException("failed to serialize to JSON: " + e.getMessage());
        }
    }
}
