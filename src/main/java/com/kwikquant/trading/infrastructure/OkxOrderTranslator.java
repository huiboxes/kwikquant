package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 解析 OKX REST /api/v5/account/positions 原始响应 → PositionSnapshot 列表(4a.4)。
     *
     * <p>spike 验证:CCXT Java 4.5.67 基类 fetchPositions() 对 OKX 返空(bug),故 4a.4 fetchSnapshot
     * 用 Java HttpClient 直调 OKX REST 绕 CCXT bug,raw 响应经本纯函数解析。OKX API 无 instId 返所有非零持仓。
     *
     * <p>raw 字段(instId/posSide/lever/mgnMode/liqPx/markPx/mmr/upl/pos/avgPx)→ PositionSnapshot 12 字段。
     * instId 反向翻译 canonical(BTC-USDT-SWAP → BTC/USDT)。纯函数便于单测。
     */
    static List<CcxtOrderAdapter.PositionSnapshot> parsePositionsRest(List<Map<String, Object>> rawList) {
        List<CcxtOrderAdapter.PositionSnapshot> out = new ArrayList<>();
        if (rawList == null) {
            return out;
        }
        for (Map<String, Object> raw : rawList) {
            String instId = stringOf(raw.get("instId"));
            String posSide = stringOf(raw.get("posSide"));
            out.add(new CcxtOrderAdapter.PositionSnapshot(
                    reverseSymbol(instId), // BTC-USDT-SWAP → BTC/USDT
                    posSide, // side long/short(net 模式可能 "net")
                    toBd(raw.get("pos")), // qty
                    toBd(raw.get("avgPx")), // entryPrice
                    MarketType.PERP, // marketType(PERP 持仓)
                    parsePositionSide(posSide), // positionSide LONG/SHORT(net/null → null)
                    toInt(raw.get("lever")), // leverage
                    parseMarginMode(stringOf(raw.get("mgnMode"))), // marginMode ISOLATED/CROSS
                    toBd(raw.get("liqPx")), // liquidationPrice
                    toBd(raw.get("markPx")), // markPrice
                    toBd(raw.get("mmr")), // maintMargin
                    toBd(raw.get("upl")) // unrealizedPnl
                    ));
        }
        return out;
    }

    /** OKX instId(BTC-USDT-SWAP)→ canonical(BTC/USDT)。base-quote-type 取 base/quote。 */
    static String reverseSymbol(String instId) {
        if (instId == null || instId.isBlank()) {
            return instId;
        }
        String[] parts = instId.split("-");
        if (parts.length < 2) {
            return instId; // 非标准格式原样返
        }
        return parts[0] + "/" + parts[1];
    }

    /** OKX posSide("long"/"short"/"net")→ PositionSide。"net" 模式返 null(单向持仓)。 */
    static PositionSide parsePositionSide(String posSide) {
        if (posSide == null || "net".equals(posSide)) {
            return null;
        }
        return "long".equals(posSide) ? PositionSide.LONG : PositionSide.SHORT;
    }

    /** OKX mgnMode("isolated"/"cross")→ MarginMode。null/空返 null。 */
    static MarginMode parseMarginMode(String mgnMode) {
        if (mgnMode == null || mgnMode.isBlank()) {
            return null;
        }
        return MarginMode.valueOf(mgnMode.toUpperCase());
    }

    /**
     * 解析 OKX REST /api/v5/fills 原始响应 → FillEvent 列表(4b 路线 B 轮询)。
     *
     * <p>raw 字段(ordId/tradeId/fillPx/fillSz/fee/feeCcy/execType/ts)→ FillEvent。spike 验证 OKX
     * /api/v5/trade/fills 字段名是 fillPx/fillSz(非 px/qty),fee/feeCcy/execType/ts 同名。
     * {@code orderId} 留 0L(纯函数无 OrderMapper),由 {@link DefaultCcxtOrderAdapter#subscribeFills}
     * 查 {@code orderMapper.findByExchangeOrderId} 填(封装 exchangeOrderId→本地 orderId 边界)。
     * {@code execType}: T→taker / M→maker(对齐 CCXT Trade.takerOrMaker)。
     * {@code ts}: 毫秒字符串 → {@link java.time.Instant}。
     */
    static List<CcxtOrderAdapter.FillEvent> parseFillsRest(List<Map<String, Object>> rawList) {
        List<CcxtOrderAdapter.FillEvent> out = new ArrayList<>();
        if (rawList == null) {
            return out;
        }
        for (Map<String, Object> raw : rawList) {
            out.add(new CcxtOrderAdapter.FillEvent(
                    0L, // orderId 由 adapter 查 OrderMapper 填(纯函数不碰 DB)
                    stringOf(raw.get("ordId")), // exchangeOrderId
                    stringOf(raw.get("tradeId")), // externalFillId(OKX 成交 ID)
                    toBd(raw.get("fillPx")), // price(OKX 字段 fillPx,非 px)
                    toBd(raw.get("fillSz")), // qty(OKX 字段 fillSz,非 qty)
                    toBd(raw.get("fee")), // fee(OKX 返负数,扣的)
                    stringOf(raw.get("feeCcy")), // feeCurrency
                    execTypeToLiquidity(stringOf(raw.get("execType"))), // liquidity T→taker/M→maker
                    toInstant(raw.get("ts")) // filledAt ms → Instant(OKX 字段 ts,非 fillTime)
                    ));
        }
        return out;
    }

    /** OKX execType("T"/"M")→ liquidity("taker"/"maker")。对齐 CCXT Trade.takerOrMaker。null 返 null。 */
    static String execTypeToLiquidity(String execType) {
        if (execType == null) {
            return null;
        }
        return "T".equals(execType) ? "taker" : ("M".equals(execType) ? "maker" : execType);
    }

    /** OKX ts(毫秒字符串)→ Instant。null/非数字返 null。 */
    private static java.time.Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return java.time.Instant.ofEpochMilli(Long.parseLong(o.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOf(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
