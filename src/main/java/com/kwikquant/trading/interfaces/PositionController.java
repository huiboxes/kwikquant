package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.TimeInForce;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持仓 REST API。
 *
 * <ul>
 *   <li>GET /api/v1/positions?accountId={accountId}&symbol={symbol}（可选）
 *   <li>POST /api/v1/positions/{positionId}/close — 平仓（反向市价单）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/positions")
@Tag(name = "持仓")
public class PositionController {

    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final MarketDataService marketDataService;
    private final TradingService tradingService;

    public PositionController(
            PositionService positionService,
            ExchangeAccountService accountService,
            MarketDataService marketDataService,
            TradingService tradingService) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.marketDataService = marketDataService;
        this.tradingService = tradingService;
    }

    @GetMapping
    @Operation(summary = "查询持仓", description = "需 JWT 鉴权。按账户 + 可选 symbol 返回持仓列表，含未实现盈亏和当前市价。后端校验账户归属，越权返回 403（1002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    public ApiResponse<List<PositionDto>> list(
            @Parameter(description = "账户 ID，鉴权校验归属", example = "42") @RequestParam long accountId,
            @Parameter(description = "按 canonical symbol 过滤，为空则返回该账户全部持仓", example = "BTC/USDT")
                    @RequestParam(required = false)
                    String symbol) {
        long currentUserId = SecurityUtils.currentUserId();
        ExchangeAccount account = accountService.getOwned(accountId, currentUserId);

        List<Position> positions;
        if (symbol != null && !symbol.isBlank()) {
            Position p = positionService.findByAccountAndSymbol(accountId, symbol);
            positions = p != null ? List.of(p) : List.of();
        } else {
            positions = positionService.findByAccount(accountId);
        }
        List<PositionDto> dtos =
                positions.stream().map(pos -> toDto(pos, account.getExchange())).toList();
        return ApiResponse.ok(dtos);
    }

    @PostMapping("/{positionId}/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "平仓",
            description = "以反向市价单平掉指定持仓的全部数量。需 JWT 鉴权，校验账户归属。" + "FLAT 或不存在的持仓返回 404（4001）。走完整下单链路（风控+余额冻结+路由）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "持仓不存在或已平（4001 RESOURCE_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    public ApiResponse<OrderSubmitResult> close(
            @Parameter(description = "持仓 ID", example = "128") @PathVariable long positionId) {
        long currentUserId = SecurityUtils.currentUserId();
        Position pos = positionService.findById(positionId);
        if (pos == null || pos.isFlat()) {
            throw new ResourceNotFoundException("position");
        }

        ExchangeAccount account = accountService.getOwned(pos.getAccountId(), currentUserId);

        OrderSide closeSide = Position.SIDE_LONG.equalsIgnoreCase(pos.getSide()) ? OrderSide.SELL : OrderSide.BUY;
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                pos.getAccountId(),
                pos.getSymbol(),
                MarketType.SPOT,
                closeSide,
                OrderType.MARKET,
                pos.getQty(),
                null,
                null,
                TimeInForce.GTC,
                null,
                null);

        OrderSubmitResult result = tradingService.submit(cmd);
        return ApiResponse.ok(result);
    }

    private PositionDto toDto(Position pos, Exchange exchange) {
        BigDecimal currentPrice = getCurrentPrice(pos.getSymbol(), exchange);
        BigDecimal unrealizedPnl = calcUnrealizedPnl(pos, currentPrice);
        return new PositionDto(
                pos.getId(),
                pos.getAccountId(),
                pos.getSymbol(),
                pos.getSide(),
                pos.getQty(),
                pos.getAvgEntryPrice(),
                pos.getRealizedPnl(),
                unrealizedPnl,
                currentPrice,
                pos.getVersion(),
                pos.getLeverage(),
                pos.getMarginMode() != null ? pos.getMarginMode().name() : null,
                pos.getPositionSide(),
                pos.getLiquidationPrice(),
                pos.getMaintMargin(),
                pos.getFrozenAmount(),
                pos.getUpdatedAt());
    }

    private BigDecimal getCurrentPrice(String symbol, Exchange exchange) {
        Ticker ticker = marketDataService.getLatestTicker(exchange, MarketType.SPOT, symbol);
        if (ticker != null && ticker.last() != null) {
            return ticker.last();
        }
        ticker = marketDataService.getLatestTicker(exchange, MarketType.PERP, symbol);
        return ticker != null ? ticker.last() : null;
    }

    private static BigDecimal calcUnrealizedPnl(Position pos, BigDecimal currentPrice) {
        if (currentPrice == null || pos.isFlat() || pos.getQty() == null || pos.getAvgEntryPrice() == null) {
            return null;
        }
        BigDecimal diff = currentPrice.subtract(pos.getAvgEntryPrice());
        if (Position.SIDE_SHORT.equalsIgnoreCase(pos.getSide())) {
            diff = diff.negate();
        }
        return diff.multiply(pos.getQty());
    }
}
