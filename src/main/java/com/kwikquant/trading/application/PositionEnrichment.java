package com.kwikquant.trading.application;

import java.math.BigDecimal;

/**
 * 持仓视图聚合结果(行情富化)。由 {@link PositionEnricher#enrich} 产出,供 REST
 * {@code PositionController.toDto} 与 MCP {@code PositionView.from} 共用(DRY,避免行情拉取 +
 * 资金费聚合逻辑两处漂移)。
 *
 * <p>三字段均依赖实时行情 / 聚合查询,不入库:
 * <ul>
 *   <li>{@code currentPrice} — 当前市价(SPOT 优先,fallback PERP),行情不可用时 null</li>
 *   <li>{@code unrealizedPnl} — 未实现盈亏,复用 {@link com.kwikquant.trading.domain.Position#getUnrealizedPnl};
 *       flat / 无市价时 null</li>
 *   <li>{@code cumulativeFunding} — 该 symbol 累计资金费结算(SUM funding_settlements),SPOT 为 0</li>
 * </ul>
 */
public record PositionEnrichment(BigDecimal currentPrice, BigDecimal unrealizedPnl, BigDecimal cumulativeFunding) {}
