package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import java.util.Map;

/**
 * 交易所订单翻译器 (策略模式). 把 {@link Order} 业务字段 + 调用方传入的 (symbol/leverage/mode/posSide)
 * 翻译成对应交易所 CCXT API 调用所需的 params Map,隔离交易所差异。
 *
 * <p>阶段4a.3 引入:仅 OKX PERP 真实实装 ({@link OkxOrderTranslator});Binance/Bitget PERP 留账
 * (§10 B7,单向持仓模式冲突)。新交易所需新增 {@code implements ExchangeOrderTranslator} 的翻译器 +
 * 在 {@link DefaultCcxtOrderAdapter} 内按 {@link #supports(Exchange)} 路由即可。
 *
 * <p><strong>纯函数契约</strong>:{@code createOrderParams/setLeverageParams/setMarginModeParams/exchangeSymbol}
 * 只依赖入参,不调网络、不持有状态,便于直接单测(无 mock)。CCXT 实际 Exchange 调用、
 * setPositionMode 首次缓存等副作用逻辑在 {@link DefaultCcxtOrderAdapter} 内,不进翻译器——保持翻译器可独立单测。
 *
 * <p><strong>symbol 翻译内嵌</strong>:{@link #exchangeSymbol} 在翻译器内硬规则翻译(canonical → 交易所 unified symbol),
 * 不依赖 {@code market.infrastructure.CcxtExchangeRegistry}(trading 模块不能依赖 market :: infra,
 * ModularityTests 拦)。OKX USDT 本位线性 PERP = base/quote:quote 规则化。
 */
public interface ExchangeOrderTranslator {

    /**
     * 该翻译器是否支持指定交易所。用于 {@link DefaultCcxtOrderAdapter} 内按 exchange 路由翻译器。
     *
     * @param exchange 交易所枚举
     * @return true 表示此翻译器处理该交易所的下单/杠杆/保证金翻译
     */
    boolean supports(Exchange exchange);

    /**
     * 把 canonical symbol({@code base/quote},如 BTC/USDT)翻译成该交易所 CCXT 认识的 unified symbol。
     *
     * <p>OKX PERP:USDT 本位线性永续 = {@code base/quote:quote}(如 BTC/USDT:USDT)。
     * SPOT:canonical 不变({@code base/quote})。反向合约/COIN-M 等非 USDT 本位线性留账(阶段6+)。
     *
     * <p>纯函数硬规则,不调 loadMarkets(避免 trading 依赖 market.infra + 网络开销)。
     *
     * @param canonical canonical symbol(base/quote)
     * @param marketType SPOT 不变;PERP 加交易所后缀
     * @return 交易所 unified symbol(如 OKX PERP BTC/USDT:USDT)
     */
    String exchangeSymbol(String canonical, MarketType marketType);

    /**
     * 把 {@link Order} 翻译成 CCXT {@code createOrder} 调用所需的 params Map。
     *
     * <p>OKX PERP 双向持仓模式:填 {@code posSide}(long/short,从 positionEffect 派生)+
     * {@code reduceOnly}(CLOSE_* 派生 true)+ {@code tdMode}(isolated/cross,从 marginMode 派生)。
     * SPOT:positionEffect=null,返空 Map(CCXT createOrder 无需 posSide/reduceOnly/tdMode)。
     *
     * <p>纯函数,只依赖 order 字段,便于单测(无 mock)。
     *
     * @param order 业务订单(需含 positionEffect/marginMode,SPOT 可 null)
     * @return CCXT createOrder params Map(SPOT 返空 Map,PERP 含 posSide/reduceOnly/tdMode)
     */
    Map<String, Object> createOrderParams(Order order);

    /**
     * 构造 CCXT {@code setLeverage} 调用所需的 params Map。
     *
     * <p>OKX PERP:填 {@code mgnMode}(isolated/cross)+ {@code posSide}(long/short,双向持仓模式必填)。
     *
     * @param mode    保证金模式
     * @param posSide 持仓方向(OKX 双向模式必填;单向模式可传 null,翻译器忽略 posSide)
     * @return CCXT setLeverage params Map
     */
    Map<String, Object> setLeverageParams(MarginMode mode, PositionSide posSide);

    /**
     * 构造 CCXT {@code setMarginMode} 调用所需的 params Map。
     *
     * <p>spike 验证:OKX setMarginMode 必须带 {@code lever} 参数,否则 BadRequest
     * "lever should be 1-125"(即使 lever 已在 setLeverage 设过,API 仍要求该 param)。lever 来源:
     * LiveExecutor 4a.5 per (account,symbol,marginMode) 缓存当前 leverage,调 setMarginMode 时传入;
     * 调用链保证 lever 非 null。
     *
     * @param leverage 杠杆倍数(1-125,OKX 要求)
     * @return CCXT setMarginMode params Map(含 lever)
     */
    Map<String, Object> setMarginModeParams(int leverage);
}
