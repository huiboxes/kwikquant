package com.kwikquant.shared.types;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 强平事件:逐仓 PERP 持仓保证金余额跌破维持保证金时触发。
 *
 * <p>区别于 {@link RiskTriggeredEvent}:强平是 PAPER 撮合内核根据 markPrice + marginBalance
 * 派生触发的独立事件,不一定有触发订单(系统强平无 user 提交的 orderId),因此
 * <strong>{@code orderId} 可空</strong>(§11 m1-new 拍板)。{@code RiskTriggeredEvent}
 * 则是 pre-trade 风控拒单,一定有被拒订单,故 {@code requireNonNull(orderId)}。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code userId} — 持仓所属用户,供通知/WX 推送定位。</li>
 *   <li>{@code orderId} — 触发强平的订单 ID;无触发订单(纯 markPrice 跌破)时为 null。</li>
 *   <li>{@code positionId} — 被强平的持仓 ID。</li>
 *   <li>{@code positionSide} — 合约持仓方向 LONG/SHORT。</li>
 *   <li>{@code leverage} — 持仓杠杆。</li>
 *   <li>{@code liquidationPrice} — 强平价(派生公式 §3.2)。</li>
 *   <li>{@code markPrice} — 触发时刻标记价。</li>
 *   <li>{@code marginBalance} — 触发时刻保证金余额(frozenAmount + unrealizedPnl)。</li>
 *   <li>{@code realizedPnl} — 强平后该持仓已实现盈亏。</li>
 *   <li>{@code reason} — 触发原因文案。</li>
 *   <li>{@code timestamp} — 触发时刻。</li>
 * </ul>
 */
public record LiquidationEvent(
        long userId,
        Long orderId,
        long accountId,
        long positionId,
        String positionSide,
        Integer leverage,
        BigDecimal liquidationPrice,
        BigDecimal markPrice,
        BigDecimal marginBalance,
        BigDecimal realizedPnl,
        String reason,
        Instant timestamp) {

    public LiquidationEvent {
        // userId / accountId / positionId 为原始 long 不可空,无需校验
        Objects.requireNonNull(positionSide, "positionSide");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
        // orderId 可空(强平无触发订单,§11 m1-new 拍板)
        // leverage / liquidationPrice / markPrice / marginBalance / realizedPnl 可空(派生过程中可能未算出)
    }
}
