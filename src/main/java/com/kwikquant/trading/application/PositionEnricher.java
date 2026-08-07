package com.kwikquant.trading.application;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.FundingSettlementMapper;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 持仓视图富化器。拉实时行情 + 聚合累计资金费,产出 {@link PositionEnrichment} 供 REST
 * {@code PositionController.toDto} 与 MCP {@code PositionView.from} 共用(DRY,避免行情拉取 +
 * 资金费聚合逻辑两处漂移)。
 *
 * <p>不放 {@link PositionService}:PositionService 被 ExecutionService 在事务内调用,职责是持仓
 * domain 增删(delta/applyPerpDelta),不该带行情拉取副作用 + 依赖 market 模块。富化是只读视图聚合,
 * 独立组件更内聚(PositionService 测试不被行情 mock 污染)。
 */
@Component
public class PositionEnricher {

    private final MarketDataService marketDataService;
    private final FundingSettlementMapper fundingSettlementMapper;

    public PositionEnricher(MarketDataService marketDataService, FundingSettlementMapper fundingSettlementMapper) {
        this.marketDataService = marketDataService;
        this.fundingSettlementMapper = fundingSettlementMapper;
    }

    /**
     * 富化单持仓:拉当前市价(持仓主市场类型优先,另一边 fallback)→ 算未实现盈亏(复用 domain
     * {@link Position#getUnrealizedPnl},避免与 PositionController 旧 calcUnrealizedPnl 口径漂移)
     * → 聚合该 symbol 累计资金费。
     *
     * <p>逐仓拉行情(N 次 getLatestTicker,与 REST 现状一致,不恶化;批量优化暂不做)。SPOT 持仓
     * cumulativeFunding 为 0(无资金费语义)。
     */
    public PositionEnrichment enrich(Position pos, Exchange exchange) {
        BigDecimal currentPrice = getCurrentPrice(pos, exchange);
        BigDecimal unrealizedPnl = pos.getUnrealizedPnl(currentPrice);
        BigDecimal cumulativeFunding = pos.getMarginMode() != null
                ? fundingSettlementMapper.sumFundingAmountByAccountAndSymbol(pos.getAccountId(), pos.getSymbol())
                : BigDecimal.ZERO;
        return new PositionEnrichment(currentPrice, unrealizedPnl, cumulativeFunding);
    }

    /**
     * 持仓主市场类型优先(SPOT 持仓查 SPOT,PERP 持仓查 PERP),另一边 fallback。
     *
     * <p>旧 PositionController.getCurrentPrice 固定 SPOT 优先,PERP 持仓用 SPOT 价算未实现盈亏
     * (基差偏差高杠杆放大),提取到 PositionEnricher 时顺手修正口径——一处改,REST/MCP 同源。
     */
    private BigDecimal getCurrentPrice(Position pos, Exchange exchange) {
        MarketType primary = pos.getMarketType();
        MarketType fallback = primary == MarketType.PERP ? MarketType.SPOT : MarketType.PERP;
        Ticker ticker = marketDataService.getLatestTicker(exchange, primary, pos.getSymbol());
        if (ticker != null && ticker.last() != null) {
            return ticker.last();
        }
        ticker = marketDataService.getLatestTicker(exchange, fallback, pos.getSymbol());
        return ticker != null ? ticker.last() : null;
    }
}
