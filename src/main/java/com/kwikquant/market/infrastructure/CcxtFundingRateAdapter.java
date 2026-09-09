package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.FundingRateHistoryPoint;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * CCXT {@code io.github.ccxt.types.FundingRate} → KwikQuant {@link FundingRate} 转换器。
 *
 * <p>CCXT Java 的 {@code fetchFundingRate} 返回 {@code CompletableFuture<Object>}，完成值由 service 层
 * 用 {@code new FundingRate(raw)} 包装后传入本适配器。Double 字段可为 null（交易所实现差异），转
 * {@link BigDecimal} 时保留 null。
 *
 * <p>期次相关字段的来源（CCXT 4.5.77 行为，字节码核实）：OKX 的 unified {@code fundingRate} ← raw
 * {@code fundingRate}（当期预估），{@code fundingTimestamp} ← raw {@code fundingTime}（未来结算时刻），
 * {@code interval} 由 nextFundingTime−fundingTime 派生（"8h" 等）；<b>已结算值 settFundingRate 不进任何
 * unified 字段、只存活于 {@code info}</b>，此处显式提取。OKX 的 unified {@code markPrice}/{@code timestamp}
 * 恒为 null（raw payload 无 markPx），调用方不得依赖。
 */
public final class CcxtFundingRateAdapter {

    private CcxtFundingRateAdapter() {}

    public static FundingRate toKwikquant(
            io.github.ccxt.types.FundingRate fr, Exchange exchange, MarketType marketType, String symbol) {
        Objects.requireNonNull(fr, "ccxt funding rate");
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Instant timestamp = fr.timestamp != null ? Instant.ofEpochMilli(fr.timestamp) : null;
        Instant fundingTime = fr.fundingTimestamp != null ? Instant.ofEpochMilli(fr.fundingTimestamp) : null;
        Instant nextFundingTime =
                fr.nextFundingTimestamp != null ? Instant.ofEpochMilli(fr.nextFundingTimestamp) : null;
        return new FundingRate(
                exchange,
                marketType,
                symbol,
                toBigDecimal(fr.fundingRate),
                fundingTime,
                parseIntervalSeconds(fr.interval),
                extractSettledRate(fr.info),
                toBigDecimal(fr.markPrice),
                toBigDecimal(fr.nextFundingRate),
                nextFundingTime,
                timestamp,
                Instant.now());
    }

    /**
     * CCXT {@code fetchFundingRateHistory} 完成值 → 已结算期次点列表。完成值通常是
     * {@code List<FundingRateHistory>}；防御性兼容 {@code List<Map>}（unified 键 timestamp/fundingRate）。
     * timestamp/rate 缺失的条目直接跳过：宁缺勿存脏期次键，缺口由采集器下一轮 history sweep 补。
     */
    public static List<FundingRateHistoryPoint> toHistoryPoints(Object raw, String symbol) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<FundingRateHistoryPoint> out = new ArrayList<>(list.size());
        for (Object element : list) {
            FundingRateHistoryPoint p = toHistoryPoint(element, symbol);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static FundingRateHistoryPoint toHistoryPoint(Object element, String symbol) {
        if (element instanceof io.github.ccxt.types.FundingRateHistory h) {
            if (h.timestamp == null || h.fundingRate == null) return null;
            return new FundingRateHistoryPoint(symbol, Instant.ofEpochMilli(h.timestamp), toBigDecimal(h.fundingRate));
        }
        if (element instanceof Map<?, ?> m) {
            Instant time = toInstant(m.get("timestamp"));
            BigDecimal rate = toDecimal(m.get("fundingRate"));
            if (time == null || rate == null) return null;
            return new FundingRateHistoryPoint(symbol, time, rate);
        }
        return null;
    }

    /**
     * CCXT interval 字符串 → 秒。OKX 派生值域 "1h/2h/4h/8h/16h/24h"，Binance 由 fundingIntervalHours
     * 拼 "Nh"；兼容 "Nm"/"Nd"。非整数格式（"0.5h"/垃圾值）返回 null，消费方按缺周期处理。
     */
    static Integer parseIntervalSeconds(String interval) {
        if (interval == null || interval.isBlank()) return null;
        String v = interval.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("h")) return Math.toIntExact(Long.parseLong(v.substring(0, v.length() - 1)) * 3600);
            if (v.endsWith("m")) return Math.toIntExact(Long.parseLong(v.substring(0, v.length() - 1)) * 60);
            if (v.endsWith("d")) return Math.toIntExact(Long.parseLong(v.substring(0, v.length() - 1)) * 86400);
        } catch (RuntimeException ignored) {
            // 落 null,不猜周期
        }
        return null;
    }

    /** OKX raw {@code info.settFundingRate}（十进制字符串）→ BigDecimal；其他所无此字段，返回 null。 */
    private static BigDecimal extractSettledRate(Map<String, Object> info) {
        if (info == null) return null;
        return toDecimal(info.get("settFundingRate"));
    }

    private static BigDecimal toBigDecimal(Double d) {
        return d != null ? BigDecimal.valueOf(d) : null;
    }

    /** 费率走字符串构造，避免 double 二进制残差（raw 值多为十进制字符串，Double 兜底）。 */
    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null;
    }
}
