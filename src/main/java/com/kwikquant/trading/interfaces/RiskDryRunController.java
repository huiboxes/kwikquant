package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.Symbol;
import com.kwikquant.trading.application.OrderMetricsService;
import com.kwikquant.trading.domain.InvalidOrderException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风控预检 REST API。
 *
 * <p>{@code POST /api/v1/risk/dry-run}：用与真实下单（{@code TradingService.submit}）<b>完全相同</b>
 * 的计算路径评估风控 verdict，但<b>不落订单、不冻结余额、不写 RiskDecision、不发事件</b>。
 *
 * <p>控制器位于 trading 模块而非 risk 模块：dry-run 需 orderMapper / fillMapper / marketDataService
 * 计算 recentOrderCount / dailyRealizedPnl / 名义额，这些都在 trading 模块；risk 模块不能依赖 trading
 * （会成环，trading 已依赖 risk）。控制器调 {@link RiskService#evaluate}（无副作用核心评估），
 * 不调 {@link RiskService#check}（后者会持久化 decision）。
 *
 * <p>越权访问他人账户返回 404（防探测），与 {@code RiskDecisionController} 一致。
 */
@RestController
@RequestMapping("/api/v1/risk/dry-run")
@Tag(name = "风控预检")
public class RiskDryRunController {

    private static final Logger log = LoggerFactory.getLogger(RiskDryRunController.class);

    private final ExchangeAccountService accountService;
    private final OrderMetricsService orderMetrics;
    private final RiskService riskService;
    private final BalanceService balanceService;

    public RiskDryRunController(
            ExchangeAccountService accountService,
            OrderMetricsService orderMetrics,
            RiskService riskService,
            BalanceService balanceService) {
        this.accountService = accountService;
        this.orderMetrics = orderMetrics;
        this.riskService = riskService;
        this.balanceService = balanceService;
    }

    @PostMapping
    @Operation(
            summary = "风控预检（不下单）",
            description = "需 JWT 鉴权。用与真实下单相同的计算路径评估风控 verdict，"
                    + "不落订单、不冻结余额、不写 RiskDecision、不发事件。"
                    + "越权访问他人账户返回 404（防探测，不返回 403）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "账户不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND，越权也返 404 防探测）")
    public ApiResponse<RiskDryRunResult> dryRun(@Valid @RequestBody RiskDryRunRequest req) {
        long userId = SecurityUtils.currentUserId();
        ExchangeAccount account = getOwned(req.accountId(), userId);

        // 与 submit 同源计算路径：marketPrice / notional / recentOrderCount / dailyPnl
        BigDecimal marketPrice =
                orderMetrics.resolveMarketPrice(account, req.side(), req.symbol(), req.marketType(), req.price());
        // 镜像 submit 的 MARKET BUY fail-fast 守卫：无有效市价时与 submit 同源拒绝（交 TradingExceptionHandler）。
        // 否则 notional=null 在无 MAX_NOTIONAL 策略账户会 false-APPROVE，违背 faithfulness（§6 B1）。
        if (orderMetrics.marketBuyLacksPrice(req.orderType(), req.side(), marketPrice)) {
            throw new InvalidOrderException("MARKET BUY requires valid ticker price, but none available");
        }
        BigDecimal notional = orderMetrics.notional(req.amount(), req.price(), marketPrice);
        // previewRecentOrderCount = countRecentOrders + 1：模拟"提交此单后"计数，精确还原 submit 在
        // insertOrder(REQUIRES_NEW 独立 tx) 后 countRecentOrders 必然含当前单的 N+1（§6 新#1）。
        int recentOrderCount = orderMetrics.previewRecentOrderCount(req.accountId());
        BigDecimal dailyPnl = orderMetrics.dailyRealizedPnl(req.accountId());

        // PERP 才查可用保证金填 availableMargin/totalBalance(与 TradingService.submit PERP 分支同源),
        // 让 MaxInitialMarginEvaluator 能评 initialMargin = notional/leverage <= availableMargin × ratio。
        // SPOT 不查(MaxNotionalEvaluator 不需要余额,省 fetchBalance 开销,availableMargin/totalBalance=null)。
        BigDecimal availableMargin = null;
        BigDecimal totalBalance = null;
        if (req.marketType() == MarketType.PERP) {
            String quoteCcy = Symbol.splitQuoteCurrency(req.symbol());
            try {
                BalanceSnapshot snap = balanceService.fetchBalance(req.accountId(), userId, MarketType.PERP);
                BalanceSnapshot.CurrencyBalance cb = snap.currencies().get(quoteCcy);
                if (cb != null) {
                    availableMargin = cb.free();
                    totalBalance = cb.total();
                }
            } catch (RuntimeException e) {
                // 与 submit 一致:fetchBalance 失败 → availableMargin/totalBalance=null
                // → MaxInitialMarginEvaluator fail-closed 拒(宁可误拦,风控正确性优先)
                log.warn(
                        "[risk-dry-run] fetchBalance for margin failed: accountId={} error={}",
                        req.accountId(),
                        e.getMessage());
            }
        }

        RiskCheckRequest riskReq = new RiskCheckRequest(
                0L, // dry-run 无真实订单
                req.accountId(),
                userId,
                req.symbol(),
                req.side(),
                req.orderType(),
                req.amount(),
                req.price(),
                notional,
                recentOrderCount,
                dailyPnl,
                req.marketType(),
                req.leverage(),
                availableMargin,
                totalBalance,
                null, // marginMode: dry-run 不查 PositionMapper(无 order.marginMode 上下文),默认 ISOLATED 行为
                null, // crossSum: dry-run 不查现有仓 frozenAmount
                "dryrun-" + UUID.randomUUID()); // 前缀标识，不与真实 check 的 requestId 混淆

        // 关键：调 evaluate（无副作用），不调 check（会 insert decision）
        RiskDecision decision = riskService.evaluate(riskReq);

        return ApiResponse.ok(new RiskDryRunResult(
                decision.getVerdict(), notional, recentOrderCount, dailyPnl, decision.getRuleResults()));
    }

    /** Verify ownership; throw 404 (not 403) to prevent accountId existence probing. */
    private ExchangeAccount getOwned(long accountId, long userId) {
        try {
            return accountService.getOwned(accountId, userId);
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("exchange account " + accountId);
        }
    }
}
