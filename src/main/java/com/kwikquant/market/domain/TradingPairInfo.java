package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;

/**
 * 交易对元信息(从 CCXT market 结构抽取)。
 *
 * <p><b>单位契约</b>:域内规范单位=币数量(base coin)。{@code minQty/maxQty/stepSize} 在装载时
 * 已币化——PERP 的交易所原始 minSz/maxSz/lotSz 是张单位,parseMarket 乘 {@code contractSize}
 * 换算后才进本 record;下游(Order.validate/风控/账本)拿到的全部是币语义,零换算。张数只存在于
 * 交易所边界适配器(DefaultCcxtOrderAdapter 经 PerpMath.toContracts/toCoin)。
 *
 * @param symbol       canonical symbol(base/quote,如 BTC/USDT)。PERP 的 :USDT 合约后缀已剥离,
 *                     与订单/持仓/行情全链路的 canonical 契约一致
 * @param ccxtSymbol   CCXT unified symbol(PERP 如 BTC/USDT:USDT,SPOT 同 canonical)。边界专用:
 *                     adapter 下单/订阅直接消费,消除硬编码后缀规则反查
 * @param minQty       最小下单量,币单位(PERP = 交易所 minSz × contractSize)
 * @param maxQty       最大下单量,币单位(交易所未声明为 null=不限)
 * @param tickSize     价格最小变动,quote 计价(与合约尺寸无关,不换算)
 * @param stepSize     数量步长,币单位(PERP = lotSz × contractSize)
 * @param contractSize 单张合约的币数量(OKX ctVal,如 BTC-USDT-SWAP=0.01);SPOT 恒 1。
 *                     PERP 装载时缺失即整条跳过(fail-closed,换算语义破坏的 pair 不可用)
 * @param maxLeverage  该交易对允许的最大杠杆(仅 PERP,来自 CCXT market.limits.leverage.max,
 *                     OKX swap 实测 100)。SPOT 恒 null;PERP 未声明时 Order.validate fail-closed 拒单——
 *                     PAPER 无交易所拒单兜底,缺此校验会撮合 1000x 等不真实单
 */
public record TradingPairInfo(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String ccxtSymbol,
        String baseAsset,
        String quoteAsset,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal contractSize,
        boolean active,
        Integer maxLeverage) {

    /**
     * SPOT 便捷构造:ccxtSymbol=canonical、contractSize=1、maxLeverage=null。
     * PERP 必须走 13 参规范构造(装载时币化 + contractSize/maxLeverage 必填语义)。
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
        this(
                exchange,
                marketType,
                symbol,
                symbol,
                baseAsset,
                quoteAsset,
                minQty,
                maxQty,
                tickSize,
                stepSize,
                BigDecimal.ONE,
                active,
                null);
    }
}
