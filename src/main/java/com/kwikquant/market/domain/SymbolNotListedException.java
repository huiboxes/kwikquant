package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;

/**
 * 请求的 canonical symbol 在指定 (交易所, 市场类型) 的 CCXT markets 表里查不到时抛出。
 *
 * <p>典型场景:yaml 配 {@code BTC/USDT}(canonical spot 形式),但该交易所的 PERP(swap)markets
 * 里 unified symbol 是 {@code BTC/USDT:USDT}——经 {@code CcxtExchangeRegistry#ccxtSymbol} 反查
 * {@code base/quote → unified symbol} 索引仍命不到(说明该交易所确实不提供这个交易对的该市场类型)。
 *
 * <p>语义是"调用方请求了一个该交易所不挂牌的 symbol",属 400 BAD_REQUEST 类(区别于
 * {@link com.kwikquant.shared.infra.ExchangeException} 的 502 交易所瞬态故障)。由
 * {@link com.kwikquant.market.infrastructure.MarketErrorAdvice} 映射到 VALIDATION_FAILED。
 */
public class SymbolNotListedException extends RuntimeException {

    private final Exchange exchange;
    private final MarketType marketType;
    private final String canonicalSymbol;

    public SymbolNotListedException(Exchange exchange, MarketType marketType, String canonicalSymbol) {
        super("symbol " + canonicalSymbol + " not listed on " + exchange + " " + marketType + " markets");
        this.exchange = exchange;
        this.marketType = marketType;
        this.canonicalSymbol = canonicalSymbol;
    }

    public Exchange exchange() {
        return exchange;
    }

    public MarketType marketType() {
        return marketType;
    }

    public String canonicalSymbol() {
        return canonicalSymbol;
    }
}
