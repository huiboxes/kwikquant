package com.kwikquant.mcp.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.interfaces.view.FundingSettlementView;
import com.kwikquant.mcp.interfaces.view.LiquidationView;
import com.kwikquant.mcp.interfaces.view.OrderView;
import com.kwikquant.mcp.interfaces.view.PositionView;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.FundingSettlementService;
import com.kwikquant.trading.application.LiquidationService;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 交易工具组（§3.4）。7 个 {@code @McpTool}：submit_order / cancel_order / get_positions /
 * get_open_orders / close_position / get_funding_history / get_liquidation_history。
 *
 * <p>所有下单经 {@link TradingService#submit(OrderSubmitCommand)}，与 REST/Worker 走同一 RiskGate（§11 零信任，
 * 不绕过风控）。{@code submitOrder}/{@code closePosition} 显式 catch {@link RiskRejectedException} 转
 * {@link OrderView#riskRejected(long, String)} 返 200（风控拒绝是业务结果非错误，与 REST {@code RiskExceptionHandler}
 * HTTP 200 行为对齐；MCP 路径不走 @RestControllerAdvice 故工具层自处理）。
 *
 * <p>{@code getPositions}/{@code getOpenOrders} 前置 {@link ExchangeAccountService#getOwned} 校验所有权
 * （{@link PositionService#findByAccount} / {@link TradingService#listOpenByAccount} 无 userId 入参，工具层补校验
 * 防越权读他人持仓/挂单；不通过抛 OwnershipViolationException 1002）。{@code submitOrder}/{@code closePosition}
 * 不需工具层前置——{@link TradingService#submit} / {@link TradingService#closePosition(long)} 内部已校验归属。
 *
 * <p>{@code closePosition}: 委托 {@link TradingService#closePosition(long)}(查持仓 → 鉴权 → 派生反向市价单
 * → submit)。下沉自 PositionController.close,REST 与 MCP 共用平仓 command 构造逻辑(DRY)。flat/空持仓
 * 抛 4001,越权抛 1002。
 *
 * <p>{@code getPositions} 持仓视图富化(当前市价/未实现盈亏/累计资金费)委托 {@link PositionEnricher},
 * 与 REST {@code PositionController.toDto} 共用(DRY)。
 *
 * <p>入参枚举（side/orderType/marketType）用 {@link #parseParam} 包装 valueOf+try-catch 转 10002
 * （Exchange/MarketType/OrderSide/OrderType 现无 fromString，不改枚举 YAGNI）。
 */
@Component
public class TradingTools {

    private final TradingService tradingService;
    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final PositionEnricher positionEnricher;
    private final FundingSettlementService fundingSettlementService;
    private final LiquidationService liquidationService;

    public TradingTools(
            TradingService tradingService,
            PositionService positionService,
            ExchangeAccountService accountService,
            PositionEnricher positionEnricher,
            FundingSettlementService fundingSettlementService,
            LiquidationService liquidationService) {
        this.tradingService = tradingService;
        this.positionService = positionService;
        this.accountService = accountService;
        this.positionEnricher = positionEnricher;
        this.fundingSettlementService = fundingSettlementService;
        this.liquidationService = liquidationService;
    }

    @McpTool(
            name = "submit_order",
            description = "下单(经RiskGate风控). accountId: 账户ID; marketType: spot/perp; symbol: BTC/USDT; "
                    + "side: buy/sell; orderType: market/limit; amount: 数量; price: 限价单价格(market单传null). "
                    + "PERP 必填 leverage/marginMode(isolated/cross)/positionEffect(open_long/open_short/"
                    + "close_long/close_short); SPOT 三参传 null. 风控拒绝返 status=RISK_REJECTED(200, 非错误).")
    public OrderView submitOrder(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol,
            @McpToolParam(description = "方向: buy/sell") String side,
            @McpToolParam(description = "订单类型: market/limit") String orderType,
            @McpToolParam(description = "数量") BigDecimal amount,
            @McpToolParam(description = "价格(limit单必填, market单传null)", required = false) BigDecimal price,
            @McpToolParam(description = "合约杠杆倍数(PERP 1-125, SPOT 传null)", required = false) Integer leverage,
            @McpToolParam(description = "合约保证金模式(PERP: isolated/cross, SPOT 传null)", required = false)
                    String marginMode,
            @McpToolParam(
                            description = "合约方向(PERP: open_long/open_short/close_long/close_short, SPOT 传null)",
                            required = false)
                    String positionEffect) {
        MarketType mt = parseMarketType(marketType);
        OrderSide sideParsed = parseParam(side, s -> OrderSide.valueOf(s.toUpperCase()), "side");
        OrderType orderTypeParsed = parseParam(orderType, s -> OrderType.valueOf(s.toUpperCase()), "orderType");
        OrderSubmitCommand cmd;
        if (mt == MarketType.PERP) {
            if (leverage == null || marginMode == null || positionEffect == null) {
                throw new McpToolParamInvalidException("PERP order requires leverage, marginMode, positionEffect");
            }
            cmd = OrderSubmitCommand.perp(
                    accountId,
                    symbol,
                    sideParsed,
                    orderTypeParsed,
                    amount,
                    price,
                    null,
                    TimeInForce.GTC,
                    null,
                    null,
                    leverage,
                    parseParam(marginMode, s -> MarginMode.valueOf(s.toUpperCase()), "marginMode"),
                    parseParam(positionEffect, s -> PositionEffect.valueOf(s.toUpperCase()), "positionEffect"));
        } else {
            cmd = OrderSubmitCommand.spot(
                    accountId,
                    symbol,
                    mt,
                    sideParsed,
                    orderTypeParsed,
                    amount,
                    price,
                    null,
                    TimeInForce.GTC,
                    null,
                    null);
        }
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
            description = "查账户持仓列表. accountId 须属当前PAT用户, 否则 1002. 返回各持仓合约字段"
                    + "(marginMode/leverage/liquidationPrice 等) + 当前市价/未实现盈亏/累计资金费(PERP).")
    public List<PositionView> getPositions(@McpToolParam(description = "交易所账户ID") Long accountId) {
        long userId = SecurityUtils.currentUserId();
        ExchangeAccount account = accountService.getOwned(accountId, userId);
        return positionService.findByAccount(accountId).stream()
                .map(pos -> PositionView.from(pos, positionEnricher.enrich(pos, account.getExchange())))
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
            description = "平仓(反向市价单). positionId: 持仓ID(从 get_positions 取). "
                    + "持多→SELL, 持短→BUY. flat/无持仓抛 4001. 越权抛 1002. "
                    + "PERP 自动派生 CLOSE_LONG/CLOSE_SHORT + 透传 leverage/marginMode. "
                    + "风控拒绝返 status=RISK_REJECTED(200, 非错误).")
    public OrderView closePosition(@McpToolParam(description = "持仓ID") Long positionId) {
        try {
            return OrderView.from(tradingService.closePosition(positionId));
        } catch (RiskRejectedException e) {
            return OrderView.riskRejected(e.getOrderId(), e.getReason());
        }
    }

    @McpTool(
            name = "get_funding_history",
            description = "查资金费率结算历史(PERP,8h 结算一次). accountId 须属当前PAT用户, 否则 1002. "
                    + "symbol 可省略查全部. 返回每笔结算明细(费率/金额/结算时间/持仓量). SPOT 账户返空列表.")
    public List<FundingSettlementView> getFundingHistory(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "交易对过滤(可省略, 如 BTC/USDT)", required = false) String symbol,
            @McpToolParam(description = "返回条数(默认50, 最大200)", required = false) Integer limit) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        int lim = limit != null ? Math.min(Math.max(limit, 1), 200) : 50;
        return fundingSettlementService.listByAccountAndSymbol(accountId, symbol, lim).stream()
                .map(FundingSettlementView::from)
                .toList();
    }

    @McpTool(
            name = "get_liquidation_history",
            description = "查强平历史(PERP). accountId 须属当前PAT用户, 否则 1002. "
                    + "symbol 可省略查全部. 返回每笔强平明细(强平价=markPrice/数量/已实现盈亏/时间). "
                    + "无强平返空列表. SPOT 账户返空.")
    public List<LiquidationView> getLiquidationHistory(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "交易对过滤(可省略, 如 BTC/USDT)", required = false) String symbol,
            @McpToolParam(description = "返回条数(默认50, 最大200)", required = false) Integer limit) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        int lim = limit != null ? Math.min(Math.max(limit, 1), 200) : 50;
        return liquidationService.listLiquidationsByAccount(accountId, symbol, lim).stream()
                .map(LiquidationView::from)
                .toList();
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
