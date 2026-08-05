package com.kwikquant.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账单记录(档位 B 实盘 PERP 强平/资金费率/ADL 同步,从 OKX bills 解析)。
 *
 * <p>反腐层:{@code LiquidationService}/{@code FundingSettlementService} 消费本 record(domain),
 * 不依赖 {@code CcxtOrderAdapter}(infrastructure)嵌套类型,不感知 OKX type int 码/posSide 字符串。
 * OKX raw → BillRecord 映射在 {@code OkxOrderTranslator.parseBills}:type int → {@link BillType},
 * posSide "long"/"short"/"net" → {@link PositionSide}(net 模式或 type=8 资金费率按净持仓时为 null)。
 *
 * @param accountId 本地账户 ID(从 ExchangeAccount 填,非 OKX 返)
 * @param billId OKX 账单 ID,幂等键(同一 bill 不重复处理)
 * @param type 账单类型(LIQUIDATION/FUNDING/ADL/OTHER)
 * @param symbol canonical BTC/USDT(从 OKX instId BTC-USDT-SWAP 反向翻译)
 * @param posSide 持仓方向(LONG/SHORT;net 模式或 type=8 资金费率按净持仓时为 null)
 * @param amt 资金费金额/强平金额(spike:type=8 在 OKX bills pnl 字段,非 amt)
 * @param posBal 结算后持仓量(强平后 qty 变化)
 * @param markPx 标记价(spike:type=8 在 OKX bills px 字段,非 markPx)
 * @param ts OKX 结算时刻
 */
public record BillRecord(
        long accountId,
        String billId,
        BillType type,
        String symbol,
        PositionSide posSide,
        BigDecimal amt,
        BigDecimal posBal,
        BigDecimal markPx,
        Instant ts) {}
