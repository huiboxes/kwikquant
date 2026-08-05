package com.kwikquant.shared.types;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 资金费率结算事件:OKX PERP 8h 资金费率结算落账后触发。
 *
 * <p>由 {@code FundingSettlementService.processFundingBill} 在事务提交后(afterCommit)
 * 通过 {@code ApplicationEventPublisher.publishEvent} 发出。仿 {@link LiquidationEvent} 模式:
 * afterCommit publishEvent + @EventListener 订阅 + WS broadcaster。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code userId} — 账户所属用户,供通知/WS 推送定位。</li>
 *   <li>{@code accountId} — 交易所账户 ID。</li>
 *   <li>{@code positionId} — 持仓 ID;平仓后结算时为 null。</li>
 *   <li>{@code symbol} — 交易对 CCXT 规范 BTC/USDT。</li>
 *   <li>{@code fundingRate} — 资金费率(OKX 语义:正费率多头付空头收,负费率反)。</li>
 *   <li>{@code qtyAtSettle} — 结算时持仓量。</li>
 *   <li>{@code fundingAmount} — 资金费金额(已带符号:正=收加 free,负=付扣 free;OKX 正费率多头付→LONG 传负)。</li>
 *   <li>{@code settleTime} — OKX 结算时刻。</li>
 *   <li>{@code billId} — OKX billId 幂等键;本地派生结算时为 null。</li>
 *   <li>{@code timestamp} — 事件发布时刻。</li>
 * </ul>
 */
public record FundingSettlementEvent(
        long userId,
        long accountId,
        Long positionId,
        String symbol,
        BigDecimal fundingRate,
        BigDecimal qtyAtSettle,
        BigDecimal fundingAmount,
        Instant settleTime,
        String billId,
        Instant timestamp) {

    public FundingSettlementEvent {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(qtyAtSettle, "qtyAtSettle");
        Objects.requireNonNull(fundingAmount, "fundingAmount");
        Objects.requireNonNull(settleTime, "settleTime");
        Objects.requireNonNull(timestamp, "timestamp");
        // positionId 可空(平仓后资金费率仍可能结算)
        // billId 可空(本地派生结算无 OKX billId,实盘有)
        // fundingRate 可空(OKX bills type=8 不返费率,只返 amt 金额)
    }
}
