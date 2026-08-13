package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.BillRecord;
import com.kwikquant.trading.domain.BillType;
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
 * <p><strong>OKX PERP 双向持仓模式翻译表</strong>(见 PositionEffect javadoc):
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
 * <p>Binance/Bitget PERP 待补齐(单向持仓模式冲突),分别由各自的
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
        // 反向合约(base/USD:BTC)/COIN-M 等非 USDT 本位线性暂不支持(需 loadMarkets 市场驱动翻译)。
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
        params.put("clOrdId", clientOrderId(order));
        PositionEffect effect = order.getPositionEffect();
        if (effect == null) {
            // SPOT:除稳定 clOrdId 外无需 posSide/reduceOnly/tdMode
            return params;
        }
        // PERP: posSide + reduceOnly + tdMode
        params.put("posSide", posSideString(effect));
        params.put("reduceOnly", order.isReduceOnly());
        params.put("tdMode", tdModeString(order.getMarginMode()));
        return params;
    }

    /** OKX clOrdId 最长 32 位且仅允许字母数字；数据库自增订单 ID 全局唯一，base36 后稳定且可重建。 */
    public static String clientOrderId(Order order) {
        if (order == null || order.getId() == null || order.getId() <= 0) {
            throw new IllegalArgumentException("persisted order id is required for OKX clOrdId");
        }
        return "KQ" + Long.toString(order.getId(), 36).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * {@link #clientOrderId(Order)} 的逆运算：KQ 前缀 clOrdId 反解本地订单 ID。
     * 非系统下发的 clOrdId（如用户在交易所页面手工下的单）返 null，调用方据此判定成交不可归属。
     */
    public static Long orderIdFromClientOrderId(String clOrdId) {
        if (clOrdId == null || !clOrdId.startsWith("KQ") || clOrdId.length() <= 2) {
            return null;
        }
        try {
            long id = Long.parseLong(clOrdId.substring(2), 36);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** canonical symbol 转 OKX REST instId。 */
    static String instrumentId(String canonical, MarketType marketType) {
        String base = canonical.replace('/', '-');
        return marketType == MarketType.PERP ? base + "-SWAP" : base;
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
     * 解析 OKX REST /api/v5/account/positions 原始响应 → PositionSnapshot 列表。
     *
     * <p>spike 验证:CCXT Java 4.5.67 基类 fetchPositions() 对 OKX 返空(bug),故 fetchSnapshot
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
    /**
     * 解析 OKX REST /api/v5/trade/orders-pending 原始响应 → OrderSnapshot 列表(对账)。
     * raw 字段(ordId/clOrdId/instId/side/sz/fillSz/state)→ OrderSnapshot。instId 反向翻译 canonical。
     */
    static List<CcxtOrderAdapter.OrderSnapshot> parseOpenOrdersRest(List<Map<String, Object>> rawList) {
        List<CcxtOrderAdapter.OrderSnapshot> out = new ArrayList<>();
        if (rawList == null) {
            return out;
        }
        for (Map<String, Object> raw : rawList) {
            out.add(new CcxtOrderAdapter.OrderSnapshot(
                    stringOf(raw.get("ordId")), // exchangeOrderId
                    stringOf(raw.get("clOrdId")), // clientOrderId
                    reverseSymbol(stringOf(raw.get("instId"))), // symbol(BTC-USDT-SWAP → BTC/USDT)
                    stringOf(raw.get("side")), // side buy/sell
                    toBd(raw.get("sz")), // amount
                    toBd(raw.get("fillSz")), // filledQty
                    stringOf(raw.get("state")))); // status(OKX state: live/partially_filled 等)
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
     * 解析 OKX REST /api/v5/fills 原始响应 → FillEvent 列表(路线 B 轮询)。
     *
     * <p>raw 字段(ordId/clOrdId/tradeId/fillPx/fillSz/fee/feeCcy/execType/ts)→ FillEvent。spike 验证 OKX
     * /api/v5/trade/fills 字段名是 fillPx/fillSz(非 px/qty),fee/feeCcy/execType/ts 同名。
     * {@code orderId} 留 0L(纯函数无 OrderMapper),由 {@link DefaultCcxtOrderAdapter#subscribeFills}
     * 查 {@code orderMapper.findByExchangeOrderId} 填(封装 exchangeOrderId→本地 orderId 边界);
     * PENDING_NEW 订单 exchangeOrderId 尚未落库时,改按 {@code clientOrderId}(KQ clOrdId)反查。
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
                    stringOf(raw.get("clOrdId")), // clientOrderId(OKX 字段 clOrdId)
                    stringOf(raw.get("tradeId")), // externalFillId(OKX 成交 ID)
                    toBd(raw.get("fillPx")), // price(OKX 字段 fillPx,非 px)
                    toBd(raw.get("fillSz")), // qty(OKX 字段 fillSz,非 qty)
                    okxFeeCost(raw.get("fee")), // 内部统一:普通费用为正成本,OKX 正数返佣为负成本
                    stringOf(raw.get("feeCcy")), // feeCurrency
                    execTypeToLiquidity(stringOf(raw.get("execType"))), // liquidity T→taker/M→maker
                    toInstant(raw.get("ts")) // filledAt ms → Instant(OKX 字段 ts,非 fillTime)
                    ));
        }
        return out;
    }

    /**
     * 解析 OKX REST /api/v5/account/bills 原始响应 → domain {@link BillRecord} 列表(实盘强平/资金费率/ADL 同步)。
     *
     * <p>反腐层:OKX raw → domain BillRecord(删 subType/ccy 未用字段;type int → {@link BillType};
     * posSide "long"/"short" → {@link PositionSide},net/空 → null)。instId 反向翻译 canonical
     * (BTC-USDT-SWAP → BTC/USDT)。资金费金额在 pnl 字段(非 amt),markPrice 在 px 字段(非 markPx),
     * spike 验证 testnet type=8 资金费率账单 2026-08-05。纯函数便于单测。{@code accountId} 由 adapter 填。
     */
    static List<BillRecord> parseBills(List<Map<String, Object>> rawList, long accountId) {
        List<BillRecord> out = new ArrayList<>();
        if (rawList == null) {
            return out;
        }
        for (Map<String, Object> raw : rawList) {
            Integer typeInt = toInt(raw.get("type"));
            int typeCode = typeInt == null ? 0 : typeInt;
            String posSideRaw = stringOf(raw.get("posSide"));
            out.add(new BillRecord(
                    accountId,
                    stringOf(raw.get("billId")),
                    BillType.fromOkxType(typeCode),
                    reverseSymbol(stringOf(raw.get("instId"))),
                    "long".equals(posSideRaw)
                            ? PositionSide.LONG
                            : "short".equals(posSideRaw) ? PositionSide.SHORT : null,
                    toBd(raw.get("pnl")), // 资金费金额(spike:type=8 在 pnl 字段,非 amt)
                    toBd(raw.get("posBal")),
                    toBd(raw.get("px")), // markPrice(spike:type=8 在 px 字段,非 markPx)
                    toInstant(raw.get("ts"))));
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

    /** OKX fee 是账户余额变动(扣费为负,返佣为正);领域 fee 是有符号成本,故在边界取反。 */
    static BigDecimal okxFeeCost(Object rawFee) {
        BigDecimal fee = toBd(rawFee);
        return fee == null ? null : fee.negate();
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
