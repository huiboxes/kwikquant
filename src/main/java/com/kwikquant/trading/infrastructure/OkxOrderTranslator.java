package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OKX 交易所订单翻译器 (策略模式实现)。
 *
 * <p>把 {@link Order} 业务字段翻译成 OKX CCXT API 调用所需的 params Map,纯函数无状态便于单测。
 *
 * <p><strong>OKX PERP 双向持仓模式翻译表</strong>(§4.3 / PositionEffect javadoc):
 * <ul>
 *   <li>{@code OPEN_LONG}   → posSide=long,  reduceOnly=false</li>
 *   <li>{@code OPEN_SHORT}  → posSide=short, reduceOnly=false</li>
 *   <li>{@code CLOSE_LONG}  → posSide=long,  reduceOnly=true (Order.isReduceOnly 派生)</li>
 *   <li>{@code CLOSE_SHORT} → posSide=short, reduceOnly=true (Order.isReduceOnly 派生)</li>
 * </ul>
 *
 * <p>{@code tdMode} = {@code marginMode.name().toLowerCase()} ("isolated"/"cross")。SPOT 不带
 * posSide/reduceOnly/tdMode(createOrderParams 返空 Map)。
 *
 * <p>setLeverage params:{@code mgnMode} + {@code posSide}(双向持仓必填)。
 * setMarginMode params:{@code lever}(spike 验证 OKX 强制要求,否则 BadRequest)。
 *
 * <p>阶段4a.3 实装。Binance/Bitget PERP 留账(§10 B7,单向持仓模式冲突),分别由各自的
 * BinanceOrderTranslator/BitgetOrderTranslator 在后续阶段实装。
 */
@Component
public class OkxOrderTranslator implements ExchangeOrderTranslator {

    @Override
    public boolean supports(Exchange exchange) {
        return exchange == Exchange.OKX;
    }

    @Override
    public String exchangeSymbol(String canonical, MarketType marketType) {
        if (marketType != MarketType.PERP) {
            return canonical; // SPOT:canonical 不变(BTC/USDT)
        }
        // OKX USDT 本位线性永续 unified symbol = base/quote:quote(如 BTC/USDT → BTC/USDT:USDT)。
        // 反向合约(base/USD:BTC)/COIN-M 等非 USDT 本位线性留账(阶段6+,需 loadMarkets 市场驱动翻译)。
        int slash = canonical.indexOf('/');
        if (slash < 0) {
            return canonical; // 防御:非 canonical 格式原样返,让 OKX 报 BadSymbol 暴露
        }
        String base = canonical.substring(0, slash);
        String quote = canonical.substring(slash + 1);
        return base + "/" + quote + ":" + quote;
    }

    @Override
    public Map<String, Object> createOrderParams(Order order) {
        Map<String, Object> params = new LinkedHashMap<>();
        PositionEffect effect = order.getPositionEffect();
        if (effect == null) {
            // SPOT: createOrder 无需 posSide/reduceOnly/tdMode
            return params;
        }
        // PERP: posSide + reduceOnly + tdMode
        params.put("posSide", posSideString(effect));
        params.put("reduceOnly", order.isReduceOnly());
        params.put("tdMode", tdModeString(order.getMarginMode()));
        return params;
    }

    @Override
    public Map<String, Object> setLeverageParams(MarginMode mode, PositionSide posSide) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mgnMode", tdModeString(mode));
        if (posSide != null) {
            params.put("posSide", posSideString(posSide));
        }
        return params;
    }

    @Override
    public Map<String, Object> setMarginModeParams(int leverage, PositionSide posSide) {
        // spike 验证:OKX setMarginMode 必须带 lever(否则 BadRequest "lever 1-125") + posSide(双向持仓,否则 51000 "posSide error")
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lever", leverage);
        params.put("posSide", posSideString(posSide));
        return params;
    }

    /**
     * positionEffect → posSide 字符串 ("long"/"short")。包私有便测试。
     *
     * <p>OPEN_LONG/CLOSE_LONG → "long"(多仓方向);OPEN_SHORT/CLOSE_SHORT → "short"(空仓方向)。
     */
    static String posSideString(PositionEffect effect) {
        return switch (effect) {
            case OPEN_LONG, CLOSE_LONG -> "long";
            case OPEN_SHORT, CLOSE_SHORT -> "short";
        };
    }

    /** PositionSide enum → OKX 字符串 ("long"/"short")。包私有便测试。 */
    static String posSideString(PositionSide posSide) {
        return posSide == PositionSide.LONG ? "long" : "short";
    }

    /** MarginMode → tdMode 字符串 ("isolated"/"cross")。包私有便测试。 */
    static String tdModeString(MarginMode mode) {
        return mode.name().toLowerCase();
    }
}
