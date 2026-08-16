package com.kwikquant.report.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 报告导出视图(GET /reports/{id}/export)——字段结构与 {@code BacktestSubmitRequest}
 * (POST /reports/import 的请求体)完全一致:<b>导出格式 = 导入消费格式</b>,
 * 导出文件可直接喂给 import 完成跨账号/跨环境迁移(用户决策 #4 trio 闭环)。
 *
 * <p>放 application 层(application 不依赖 interfaces;interfaces 的 BacktestSubmitRequest
 * 带 validation 注解是入站契约,本 record 是出站视图,两者按 JSON 字段名对齐)。
 *
 * @param name 报告名
 * @param params 策略参数快照(含平台附加的 _kwikquant 可复现元数据)
 * @param symbol canonical symbol
 * @param timeframe K 线周期
 * @param period 回测区间
 * @param trades 交易明细(time/side/price/amount/fee,import TradeEntry 字段集)
 * @param equityCurve 权益曲线(time/equity)
 */
public record ReportExportView(
        String name,
        Map<String, Object> params,
        String symbol,
        String timeframe,
        PeriodRange period,
        List<TradeEntry> trades,
        List<EquityPointEntry> equityCurve) {

    public record PeriodRange(Instant start, Instant end) {}

    public record TradeEntry(Instant time, String side, BigDecimal price, BigDecimal amount, BigDecimal fee) {}

    public record EquityPointEntry(Instant time, BigDecimal equity) {}
}
