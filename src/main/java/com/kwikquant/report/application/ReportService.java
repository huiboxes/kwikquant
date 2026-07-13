package com.kwikquant.report.application;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.PerformanceCalculator;
import com.kwikquant.report.domain.PerformanceMetrics;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.report.domain.TradeRecord;
import com.kwikquant.report.infrastructure.BacktestReportMapper;
import com.kwikquant.report.infrastructure.TradeRecordMapper;
import com.kwikquant.shared.types.PageDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final int MAX_TRADES = 10_000;
    private static final int MAX_EQUITY_POINTS = 50_000;

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
                userId, name, params, symbol, timeframe, periodStart, periodEnd, trades, equityCurve, "PLATFORM");
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
        return doSubmit(userId, name, params, symbol, timeframe, periodStart, periodEnd, trades, equityCurve, "IMPORT");
    }

    private BacktestReport doSubmit(
            long userId,
            String name,
            Object params,
            String symbol,
            String timeframe,
            java.time.Instant periodStart,
            java.time.Instant periodEnd,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve,
            String source) {

        // --- validation ---
        if (trades == null || trades.isEmpty()) {
            throw new ReportInvalidPayloadException("trades must not be empty");
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
        PerformanceCalculator.enrichTrades(trades);
        tradeRecordMapper.batchInsert(trades);

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
     * Wave 8 §3.6:从 §8 JSON 提交回测结果。解析 trades/equity_curve/period/meta → {@link #doSubmit} source=PLATFORM。
     *
     * <p>report 拥有 §8 解析(TradeRecord/EquityPoint 是 report/domain),避免 strategy(BacktestExecutionGateway)
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
            throw new ReportInvalidPayloadException("invalid §8 json: " + e.getMessage());
        }
        String name = root.path("name").asText("backtest");
        JsonNode paramsNode = root.path("params");
        Object params = paramsNode.isMissingNode() ? Map.of() : paramsNode;
        String symbol = root.path("symbol").asText("");
        String timeframe = root.path("timeframe").asText("");
        Instant periodStart = parsePeriod(root.path("period").path("start").asText(null));
        Instant periodEnd = parsePeriod(root.path("period").path("end").asText(null));
        List<TradeRecord> trades = parseTrades(root.path("trades"));
        List<EquityPoint> equityCurve = parseEquityCurve(root.path("equity_curve"));
        // 契约改动 B：返 reportId（long），让 BacktestExecutionGateway 回填 task.report_id 而不依赖 report::domain
        return doSubmit(
                        userId,
                        name,
                        params,
                        symbol,
                        timeframe,
                        periodStart,
                        periodEnd,
                        trades,
                        equityCurve,
                        "PLATFORM")
                .getId();
    }

    private List<TradeRecord> parseTrades(JsonNode tradesNode) {
        List<TradeRecord> trades = new ArrayList<>();
        if (tradesNode == null || !tradesNode.isArray()) return trades;
        for (JsonNode t : tradesNode) {
            TradeRecord tr = new TradeRecord();
            tr.setTime(parsePeriod(t.path("time").asText(null)));
            tr.setSide(t.path("side").asText("buy"));
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

    public PageDto<BacktestReport> listByUser(long userId, String symbol, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<BacktestReport> items = reportMapper.findByUserId(userId, symbol, pageSize, offset);
        long total = reportMapper.countByUserId(userId, symbol);
        return PageDto.of(items, page, pageSize, total);
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

    public List<EquityPoint> parseEquityCurve(String equityCurveJson) {
        if (equityCurveJson == null || equityCurveJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(equityCurveJson, new TypeReference<List<EquityPoint>>() {});
        } catch (JacksonException e) {
            log.warn("[report] failed to parse equity curve: {}", e.getMessage());
            return List.of();
        }
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
