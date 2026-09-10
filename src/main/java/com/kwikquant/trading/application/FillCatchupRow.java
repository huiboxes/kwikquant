package com.kwikquant.trading.application;

import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * runner 断线增量补拉明细行（{@code FillMapper.findCommittedSince} 读模型，
 * GET /api/v1/worker/fills-since 消费）。
 *
 * <p>{@code positionEffect}/{@code marketType} 来自所属订单 join（fills 表无这两列）：
 * marketType 是 runner 市场类型过滤键（user 级 WS topic 下同账户 SPOT/PERP 同 symbol 并存，
 * 补拉行与 WS FillEvent 载荷同构才能共用派发过滤链），positionEffect 进策略 on_fill payload。
 * 两者均可空（SPOT 单无 effect；存量 legacy 单无 market_type）。
 *
 * @param id fill 主键（BIGSERIAL，游标/去重键——worker 侧按 fillId 去重）
 * @param positionEffect 持仓意图（所属订单），SPOT/legacy null
 * @param marketType 市场类型（所属订单），legacy null
 */
public record FillCatchupRow(
        long id,
        long orderId,
        long accountId,
        String symbol,
        OrderSide side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee,
        String feeCurrency,
        String liquidity,
        Instant filledAt,
        PositionEffect positionEffect,
        MarketType marketType) {}
