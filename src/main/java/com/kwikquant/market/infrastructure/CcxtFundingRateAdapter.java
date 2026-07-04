package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * CCXT {@code io.github.ccxt.types.FundingRate} → KwikQuant {@link FundingRate} 转换器。
 *
 * <p>CCXT Java 4.5.59 的 {@code fetchFundingRate} 返回 {@code CompletableFuture<Object>}，完成值由 service 层
 * 用 {@code new FundingRate(raw)} 包装后传入本适配器。Double 字段可为 null（交易所实现差异），转 {@link BigDecimal}
 * 时保留 null。
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
        Instant nextFundingTime =
                fr.nextFundingTimestamp != null ? Instant.ofEpochMilli(fr.nextFundingTimestamp) : null;
        return new FundingRate(
                exchange,
                marketType,
                symbol,
                toBigDecimal(fr.fundingRate),
                toBigDecimal(fr.markPrice),
                toBigDecimal(fr.nextFundingRate),
                nextFundingTime,
                timestamp,
                Instant.now());
    }

    private static BigDecimal toBigDecimal(Double d) {
        return d != null ? BigDecimal.valueOf(d) : null;
    }
}
