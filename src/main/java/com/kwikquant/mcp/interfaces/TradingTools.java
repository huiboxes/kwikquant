package com.kwikquant.mcp.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.application.McpConfirmTokenService;
import com.kwikquant.mcp.application.McpScopeGuard;
import com.kwikquant.mcp.interfaces.view.ConfirmRequiredView;
import com.kwikquant.mcp.interfaces.view.FundingSettlementView;
import com.kwikquant.mcp.interfaces.view.LiquidationView;
import com.kwikquant.mcp.interfaces.view.OrderCancelPreview;
import com.kwikquant.mcp.interfaces.view.OrderSubmitPreview;
import com.kwikquant.mcp.interfaces.view.OrderView;
import com.kwikquant.mcp.interfaces.view.PositionClosePreview;
import com.kwikquant.mcp.interfaces.view.PositionView;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.McpTokenScope;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.FundingSettlementService;
import com.kwikquant.trading.application.LiquidationService;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Order;
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
 * MCP 交易工具组。7 个 {@code @McpTool}：submit_order / cancel_order / get_positions /
 * get_open_orders / close_position / get_funding_history / get_liquidation_history。
 *
 * <p><b>三层防护</b>（AI 动真钱的底线设计）：
 * <ol>
 *   <li><b>scope</b>：写工具要求 PAT 开通 TRADE（{@link McpScopeGuard#require}，未开通 10005）；
 *   <li><b>两阶段 confirmToken</b>：**实盘账户**写操作第一阶段返 {@link ConfirmRequiredView}（预览+令牌，
 *       零副作用），第二阶段携令牌复述相同参数才执行（{@link McpConfirmTokenService} 指纹绑定+一次性+短TTL）。
 *       模拟盘免确认（与 CLI 分级一致）。旧裸 boolean confirm 已废弃（可被 agent 单回合自带，不构成防线）；
 *   <li><b>幂等</b>：submit_order 提供 clientOrderId，重试复用同值不重复下单
 *       （{@link TradingService#submit} replay 机制现成）。
 * </ol>
 *
 * <p>所有下单经 {@link TradingService#submit(OrderSubmitCommand)}，与 REST/Worker 走同一 RiskGate（零信任，
 * 不绕过风控）。风控拒绝转 {@link OrderView#riskRejected(long, String)} 返业务结果（非错误）。
 *
 * <p>{@code getPositions}/{@code getOpenOrders} 前置 {@link ExchangeAccountService#getOwned} 校验所有权。
 * {@code closePosition} 平仓前置 {@link PositionService#findById} + getOwned 取 paperTrading 标记决定
 * 是否需两阶段，执行委托 {@link TradingService#closePosition(long)}（内部再校验归属，深度防御）。
 */
@Component
public class TradingTools {

    private static final String TOOL_SUBMIT = "submit_order";
    private static final String TOOL_CANCEL = "cancel_order";
    private static final String TOOL_CLOSE = "close_position";

    private final TradingService tradingService;
    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final PositionEnricher positionEnricher;
    private final FundingSettlementService fundingSettlementService;
    private final LiquidationService liquidationService;
    private final McpScopeGuard scopeGuard;
    private final McpConfirmTokenService confirmTokenService;

    public TradingTools(
            TradingService tradingService,
            PositionService positionService,
            ExchangeAccountService accountService,
            PositionEnricher positionEnricher,
            FundingSettlementService fundingSettlementService,
            LiquidationService liquidationService,
            McpScopeGuard scopeGuard,
            McpConfirmTokenService confirmTokenService) {
        this.tradingService = tradingService;
        this.positionService = positionService;
        this.accountService = accountService;
        this.positionEnricher = positionEnricher;
        this.fundingSettlementService = fundingSettlementService;
        this.liquidationService = liquidationService;
        this.scopeGuard = scopeGuard;
        this.confirmTokenService = confirmTokenService;
    }

    @McpTool(
            name = "submit_order",
            description = "下单(经RiskGate风控). **实盘账户两阶段确认**:不带 confirmToken 调用先返预览+令牌(零副作用),"
                    + "向用户确认后复述相同参数+confirmToken 再调用才执行;模拟盘直接执行. "
                    + "重试必须复用同一 clientOrderId 防重复下单. "
                    + "accountId: 账户ID; marketType: spot/perp; symbol: BTC/USDT; side: buy/sell; "
                    + "orderType: market/limit; amount: 数量; price: 限价单价格(market单传null). "
                    + "PERP 必填 leverage/marginMode(isolated/cross)/positionEffect(open_long/open_short/"
                    + "close_long/close_short); SPOT 三参传 null. 风控拒绝返 status=RISK_REJECTED(业务结果非错误).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public Object submitOrder(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol,
            @McpToolParam(description = "方向: buy/sell") String side,
            @McpToolParam(description = "订单类型: market/limit") String orderType,
            @McpToolParam(description = "数量(decimal string, 如 \"0.01\";金额一律字符串防浮点误差)") String amount,
            @McpToolParam(description = "价格(decimal string; limit单必填, market单传null)", required = false) String price,
            @McpToolParam(description = "合约杠杆倍数(PERP 1-125, SPOT 传null)", required = false) Integer leverage,
            @McpToolParam(description = "合约保证金模式(PERP: isolated/cross, SPOT 传null)", required = false)
                    String marginMode,
            @McpToolParam(
                            description = "合约方向(PERP: open_long/open_short/close_long/close_short, SPOT 传null)",
                            required = false)
                    String positionEffect,
            @McpToolParam(description = "幂等键: 重试必须复用同值防重复下单(建议 '<意图摘要>-<随机>')", required = false) String clientOrderId,
            @McpToolParam(description = "两阶段确认令牌(实盘账户第二阶段传第一阶段返回值)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.TRADE);
        long userId = SecurityUtils.currentUserId();
        ExchangeAccount account = accountService.getOwned(accountId, userId);
        MarketType mt = parseMarketType(marketType);
        OrderSide sideParsed = parseParam(side, s -> OrderSide.valueOf(s.toUpperCase()), "side");
        OrderType orderTypeParsed = parseParam(orderType, s -> OrderType.valueOf(s.toUpperCase()), "orderType");
        BigDecimal amountParsed = parseDecimal(amount, "amount");
        BigDecimal priceParsed = price == null ? null : parseDecimal(price, "price");
        MarginMode marginModeParsed = marginMode == null
                ? null
                : parseParam(marginMode, s -> MarginMode.valueOf(s.toUpperCase()), "marginMode");
        PositionEffect positionEffectParsed = positionEffect == null
                ? null
                : parseParam(positionEffect, s -> PositionEffect.valueOf(s.toUpperCase()), "positionEffect");
        if (mt == MarketType.PERP && (leverage == null || marginModeParsed == null || positionEffectParsed == null)) {
            throw new McpToolParamInvalidException("PERP order requires leverage, marginMode, positionEffect");
        }
        String canonical = canonical(
                accountId,
                mt,
                symbol,
                sideParsed,
                orderTypeParsed,
                amountParsed,
                priceParsed,
                leverage,
                marginModeParsed,
                positionEffectParsed,
                clientOrderId);
        if (!account.isPaperTrading()) {
            if (confirmToken == null || confirmToken.isBlank()) {
                var issue = confirmTokenService.issue(userId, TOOL_SUBMIT, canonical);
                return new ConfirmRequiredView(
                        TOOL_SUBMIT,
                        issue.token(),
                        issue.expiresInSec(),
                        new OrderSubmitPreview(
                                accountId,
                                account.getLabel(),
                                mt.name(),
                                symbol,
                                sideParsed.name(),
                                orderTypeParsed.name(),
                                amount,
                                price,
                                leverage,
                                marginModeParsed == null ? null : marginModeParsed.name(),
                                positionEffectParsed == null ? null : positionEffectParsed.name(),
                                clientOrderId));
            }
            confirmTokenService.consume(userId, TOOL_SUBMIT, canonical, confirmToken);
        }
        OrderSubmitCommand cmd;
        if (mt == MarketType.PERP) {
            cmd = OrderSubmitCommand.perp(
                    accountId,
                    symbol,
                    sideParsed,
                    orderTypeParsed,
                    amountParsed,
                    priceParsed,
                    null,
                    TimeInForce.GTC,
                    null,
                    clientOrderId,
                    leverage,
                    marginModeParsed,
                    positionEffectParsed);
        } else {
            cmd = OrderSubmitCommand.spot(
                    accountId,
                    symbol,
                    mt,
                    sideParsed,
                    orderTypeParsed,
                    amountParsed,
                    priceParsed,
                    null,
                    TimeInForce.GTC,
                    null,
                    clientOrderId);
        }
        try {
            OrderSubmitResult result = tradingService.submit(cmd);
            return OrderView.from(result);
        } catch (RiskRejectedException e) {
            return OrderView.riskRejected(e.getOrderId(), e.getReason());
        }
    }

    @McpTool(
            name = "cancel_order",
            description = "撤单. **实盘账户两阶段确认**:不带 confirmToken 先返预览+令牌,复述参数+令牌再执行;模拟盘直接执行. " + "orderId: 订单ID. 返回最新订单状态.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public Object cancelOrder(
            @McpToolParam(description = "订单ID") Long orderId,
            @McpToolParam(description = "两阶段确认令牌(实盘账户第二阶段传)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.TRADE);
        long userId = SecurityUtils.currentUserId();
        Order order = tradingService.getOrder(orderId); // 内含 ownership 校验(越权/不存在统一 404)
        ExchangeAccount account = accountService.getOwned(order.getAccountId(), userId);
        String canonical = canonical(orderId);
        if (!account.isPaperTrading()) {
            if (confirmToken == null || confirmToken.isBlank()) {
                var issue = confirmTokenService.issue(userId, TOOL_CANCEL, canonical);
                return new ConfirmRequiredView(
                        TOOL_CANCEL,
                        issue.token(),
                        issue.expiresInSec(),
                        new OrderCancelPreview(
                                orderId,
                                order.getAccountId(),
                                order.getSymbol(),
                                order.getSide() == null ? null : order.getSide().name(),
                                order.getOrderType() == null
                                        ? null
                                        : order.getOrderType().name(),
                                str(order.getAmount()),
                                order.getStatus() == null
                                        ? null
                                        : order.getStatus().name()));
            }
            confirmTokenService.consume(userId, TOOL_CANCEL, canonical, confirmToken);
        }
        return OrderView.from(tradingService.cancel(orderId));
    }

    @McpTool(
            name = "get_positions",
            description = "查账户持仓列表. accountId 须属当前PAT用户. 返回各持仓合约字段"
                    + "(marginMode/leverage/liquidationPrice 等) + 当前市价/未实现盈亏/累计资金费(PERP).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<PositionView> getPositions(@McpToolParam(description = "交易所账户ID") Long accountId) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        ExchangeAccount account = accountService.getOwned(accountId, userId);
        return positionService.findByAccount(accountId).stream()
                .map(pos -> PositionView.from(pos, positionEnricher.enrich(pos, account.getExchange())))
                .toList();
    }

    @McpTool(
            name = "get_open_orders",
            description =
                    "查账户未终结挂单(NEW/PENDING_NEW/SUBMITTED/PARTIALLY_FILLED/PENDING_CANCEL). " + "accountId 须属当前PAT用户.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<OrderView> getOpenOrders(@McpToolParam(description = "交易所账户ID") Long accountId) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        return tradingService.listOpenByAccount(accountId).stream()
                .map(OrderView::from)
                .toList();
    }

    @McpTool(
            name = "close_position",
            description = "平仓(反向市价单). **实盘账户两阶段确认**:不带 confirmToken 先返预览+令牌,复述参数+令牌再执行;"
                    + "模拟盘直接执行. positionId: 持仓ID(从 get_positions 取). 持多→SELL, 持短→BUY. "
                    + "PERP 自动派生 CLOSE_LONG/CLOSE_SHORT. 风控拒绝返 status=RISK_REJECTED(业务结果非错误).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public Object closePosition(
            @McpToolParam(description = "持仓ID") Long positionId,
            @McpToolParam(description = "两阶段确认令牌(实盘账户第二阶段传)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.TRADE);
        long userId = SecurityUtils.currentUserId();
        Position position = positionService.findById(positionId);
        if (position == null) {
            throw new ResourceNotFoundException("position", positionId);
        }
        ExchangeAccount account = accountService.getOwned(position.getAccountId(), userId);
        String canonical = canonical(positionId);
        if (!account.isPaperTrading()) {
            if (confirmToken == null || confirmToken.isBlank()) {
                var issue = confirmTokenService.issue(userId, TOOL_CLOSE, canonical);
                return new ConfirmRequiredView(
                        TOOL_CLOSE,
                        issue.token(),
                        issue.expiresInSec(),
                        new PositionClosePreview(
                                positionId,
                                position.getAccountId(),
                                position.getSymbol(),
                                position.getSide(),
                                str(position.getQty()),
                                position.getPositionSide()));
            }
            confirmTokenService.consume(userId, TOOL_CLOSE, canonical, confirmToken);
        }
        try {
            return OrderView.from(tradingService.closePosition(positionId));
        } catch (RiskRejectedException e) {
            return OrderView.riskRejected(e.getOrderId(), e.getReason());
        }
    }

    @McpTool(
            name = "get_funding_history",
            description = "查资金费率结算历史(PERP,8h 结算一次). accountId 须属当前PAT用户. "
                    + "symbol 可省略查全部. 返回每笔结算明细(费率/金额/结算时间/持仓量). SPOT 账户返空列表.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<FundingSettlementView> getFundingHistory(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "交易对过滤(可省略, 如 BTC/USDT)", required = false) String symbol,
            @McpToolParam(description = "返回条数(默认50, 最大200)", required = false) Integer limit) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        int lim = limit != null ? Math.min(Math.max(limit, 1), 200) : 50;
        return fundingSettlementService.listByAccountAndSymbol(accountId, symbol, lim).stream()
                .map(FundingSettlementView::from)
                .toList();
    }

    @McpTool(
            name = "get_liquidation_history",
            description = "查强平历史(PERP). accountId 须属当前PAT用户. symbol 可省略查全部. "
                    + "返回每笔强平明细(强平价=markPrice/数量/已实现盈亏/时间). 无强平返空列表. SPOT 账户返空.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<LiquidationView> getLiquidationHistory(
            @McpToolParam(description = "交易所账户ID") Long accountId,
            @McpToolParam(description = "交易对过滤(可省略, 如 BTC/USDT)", required = false) String symbol,
            @McpToolParam(description = "返回条数(默认50, 最大200)", required = false) Integer limit) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        int lim = limit != null ? Math.min(Math.max(limit, 1), 200) : 50;
        return liquidationService.listLiquidationsByAccount(accountId, symbol, lim).stream()
                .map(LiquidationView::from)
                .toList();
    }

    /** 规范化参数串(指纹用):解析后值拼接,大小写/格式归一,保证"复述相同参数"产生相同指纹。 */
    private static String canonical(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "∅" : String.valueOf(p)).append('|');
        }
        return sb.toString();
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

    /** decimal string 解析(金额红线:MCP 入参字符串,service 层 BigDecimal);非法抛参数错。 */
    private static BigDecimal parseDecimal(String raw, String name) {
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            throw new McpToolParamInvalidException("invalid " + name + " (expect decimal string): " + raw);
        }
    }

    private static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
