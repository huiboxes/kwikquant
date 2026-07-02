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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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
