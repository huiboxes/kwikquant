package com.kwikquant.mcp.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.mcp.interfaces.view.OrderView;
import com.kwikquant.mcp.interfaces.view.PositionView;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.TimeInForce;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 交易工具组（§3.4）。5 个 {@code @McpTool}：submit_order / cancel_order / get_positions /
 * get_open_orders / close_position。
 *
 * <p>所有下单经 {@link TradingService#submit(OrderSubmitCommand)}，与 REST/Worker 走同一 RiskGate（§11 零信任，
 * 不绕过风控）。{@code submitOrder}/{@code closePosition} 显式 catch {@link RiskRejectedException} 转
 * {@link OrderView#riskRejected(long, String)} 返 200（风控拒绝是业务结果非错误，与 REST {@code RiskExceptionHandler}
 * HTTP 200 行为对齐；MCP 路径不走 @RestControllerAdvice 故工具层自处理）。
 *
 * <p>{@code getPositions}/{@code getOpenOrders}/{@code closePosition} 前置 {@link ExchangeAccountService#getOwned}
 * 校验所有权（{@link com.kwikquant.trading.application.PositionService#findByAccount} /
 * {@link TradingService#listOpenByAccount} 无 userId 入参，工具层补校验防越权读他人持仓/挂单；不通过抛
 * OwnershipViolationException 1002）。{@code submitOrder} 不需前置校验——{@link TradingService#submit} 内部
 * {@code loadOwnedAccount} 已校验。
 *
 * <p>{@code closePosition}：查 {@link Position#getSide()}，持多(long)→SELL，持短(short)→BUY，反向市价单；
 * flat/空持仓抛 {@link ResourceNotFoundException} (4001)。
 *
 * <p>入参枚举（side/orderType/marketType）用 {@link #parseParam} 包装 valueOf+try-catch 转 10002
 * （Exchange/MarketType/OrderSide/OrderType 现无 fromString，不改枚举 YAGNI）。
 */
@Component
public class TradingTools {

    private final TradingService tradingService;
    private final PositionService positionService;
    private final ExchangeAccountService accountService;

    public TradingTools(
            TradingService tradingService, PositionService positionService, ExchangeAccountService accountService) {
        this.tradingService = tradingService;
        this.positionService = positionService;
        this.accountService = accountService;
    }

    @McpTool(
            name = "submit_order",
            description = "下单(经RiskGate风控). accountId: 账户ID; marketType: spot/perp; symbol: BTC/USDT; "
                    + "side: buy/sell; orderType: market/limit; amount: 数量; price: 限价单价格(market单传null). "
                    + "风控拒绝返 status=RISK_REJECTED(200, 非错误).")
    public OrderView submitOrder(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol,
            @McpToolParam(description = "方向: buy/sell") String side,
            @McpToolParam(description = "订单类型: market/limit") String orderType,
            @McpToolParam(description = "数量") BigDecimal amount,
            @McpToolParam(description = "价格(limit单必填, market单传null)", required = false) BigDecimal price) {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                accountId,
                symbol,
                parseMarketType(marketType),
                parseParam(side, s -> OrderSide.valueOf(s.toUpperCase()), "side"),
                parseParam(orderType, s -> OrderType.valueOf(s.toUpperCase()), "orderType"),
                amount,
                price,
                null,
                TimeInForce.GTC,
                null,
                null);
        try {
            OrderSubmitResult result = tradingService.submit(cmd);
            return OrderView.from(result);
        } catch (RiskRejectedException e) {
            return OrderView.riskRejected(e.getOrderId(), e.getReason());
        }
    }

    @McpTool(name = "cancel_order", description = "撤单. orderId: 订单ID. 返回最新订单状态.")
    public OrderView cancelOrder(@McpToolParam(description = "订单ID") Long orderId) {
        return OrderView.from(tradingService.cancel(orderId));
    }

    @McpTool(
            name = "get_positions",
            description = "查账户持仓列表. accountId 须属当前PAT用户, 否则 1002. 返回各交易对持仓(side/qty/均价/已实现盈亏).")
    public List<PositionView> getPositions(@McpToolParam(description = "交易所账户ID") Long accountId) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        return positionService.findByAccount(accountId).stream()
                .map(PositionView::from)
                .toList();
    }

    @McpTool(
            name = "get_open_orders",
            description = "查账户未终结挂单(NEW/PENDING_NEW/SUBMITTED/PARTIALLY_FILLED/PENDING_CANCEL). "
                    + "accountId 须属当前PAT用户, 否则 1002.")
    public List<OrderView> getOpenOrders(@McpToolParam(description = "交易所账户ID") Long accountId) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        return tradingService.listOpenByAccount(accountId).stream()
                .map(OrderView::from)
                .toList();
    }

    @McpTool(
            name = "close_position",
            description = "平仓(反向市价单). 持多→SELL, 持短→BUY. flat/无持仓抛 4001. "
                    + "accountId 须属当前PAT用户, 否则 1002. marketType/symbol 指定要平的持仓.")
    public OrderView closePosition(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId); // 前置所有权校验（findByAccountAndSymbol 无 userId 入参）
        Position position = positionService.findByAccountAndSymbol(accountId, symbol);
        if (position == null || position.isFlat()) {
            throw new ResourceNotFoundException("position");
        }
        OrderSide closeSide = Position.SIDE_LONG.equals(position.getSide()) ? OrderSide.SELL : OrderSide.BUY;
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                accountId,
                symbol,
                parseMarketType(marketType),
                closeSide,
                OrderType.MARKET,
                position.getQty(),
                null,
                null,
                TimeInForce.GTC,
                null,
                null);
        try {
            return OrderView.from(tradingService.submit(cmd));
        } catch (RiskRejectedException e) {
            return OrderView.riskRejected(e.getOrderId(), e.getReason());
        }
    }

    private static MarketType parseMarketType(String raw) {
        return parseParam(raw, s -> MarketType.valueOf(s.toUpperCase()), "marketType");
    }

    private static <T> T parseParam(String raw, Function<String, T> parser, String desc) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            throw new McpToolParamInvalidException("invalid " + desc + ": " + raw);
        }
    }
}
