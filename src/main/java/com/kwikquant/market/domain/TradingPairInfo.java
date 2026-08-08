package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;

/**
 * 交易对元信息(从 CCXT market 结构抽取)。
 *
 * @param maxLeverage 该交易对允许的最大杠杆(PERP,来自 CCXT market.limits.leverage.max);
 *     SPOT 或交易所未声明时为 null(不校验)。Order.validate 据此 pre-trade 拒绝超杠杆单——
 *     PAPER 无交易所拒单兜底,缺此校验会撮合 1000x 等不真实单;LIVE 省一次被交易所拒的往返。
 */
public record TradingPairInfo(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String baseAsset,
        String quoteAsset,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal tickSize,
        BigDecimal stepSize,
        boolean active,
        Integer maxLeverage) {

    /**
     * 兼容构造:maxLeverage 未知(交易所未声明/SPOT)时置 null。现有调用点(SPOT 对/PERP 无 leverage
     * 上限声明)走此构造,maxLeverage 校验跳过(null 语义=不校验)。PERP 带上限声明时走 11 参规范构造。
     */
    public TradingPairInfo(
            Exchange exchange,
            MarketType marketType,
            String symbol,
            String baseAsset,
            String quoteAsset,
            BigDecimal minQty,
            BigDecimal maxQty,
            BigDecimal tickSize,
            BigDecimal stepSize,
            boolean active) {
        this(exchange, marketType, symbol, baseAsset, quoteAsset, minQty, maxQty, tickSize, stepSize, active, null);
    }
}
