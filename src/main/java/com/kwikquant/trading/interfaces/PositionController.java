package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionEnrichment;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Position;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final PositionEnricher positionEnricher;
    private final TradingService tradingService;

    public PositionController(
            PositionService positionService,
            ExchangeAccountService accountService,
            PositionEnricher positionEnricher,
            TradingService tradingService) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.positionEnricher = positionEnricher;
        this.tradingService = tradingService;
    }

    @GetMapping
    @Operation(summary = "查询持仓", description = "需 JWT 鉴权。按账户 + 可选 symbol 返回持仓列表，含未实现盈亏和当前市价。后端校验账户归属，越权返回 403（1002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    public ApiResponse<List<PositionDto>> list(
            @Parameter(description = "账户 ID，鉴权校验归属（Worker 请求应为空，后端据 X-Worker-Token 推导）", example = "42")
                    @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "按 canonical symbol 过滤，为空则返回该账户全部持仓", example = "BTC/USDT")
                    @RequestParam(required = false)
                    String symbol,
            HttpServletRequest httpReq) {
        // §3.7 R4:Worker 请求由 WorkerTokenFilter 注入 (strategyId, userId, exchange) request attr;
        // 此时 accountId 应 null(Worker 不知),Controller 从 token 推导 account(防越权,同 OrderController)。
        ExchangeAccount account;
        Long workerStrategyId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR);
        if (workerStrategyId != null) {
            // worker token 绑 accountId(filter 注入),去 findByUserAndExchange 推导(去 UNIQUE)
            Long workerAccountId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_ACCOUNT_ID_ATTR);
            Long workerUserId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_USER_ID_ATTR);
            account = accountService.findById(workerAccountId);
            if (account == null || !Long.valueOf(account.getUserId()).equals(workerUserId)) {
                throw new com.kwikquant.trading.domain.InvalidOrderException(
                        "worker account not owned or not found: " + workerAccountId);
            }
        } else if (accountId == null) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("accountId required for user requests");
        } else {
            account = accountService.getOwned(accountId, SecurityUtils.currentUserId());
        }
        long effectiveAccountId = account.getId();

        List<Position> positions;
        if (symbol != null && !symbol.isBlank()) {
            // HIGH-4b:返 List(含 SPOT+PERP);旧 findByAccountAndSymbol 单行 SPOT-only 只持 PERP 时返空
            positions = positionService.findAllByAccountAndSymbol(effectiveAccountId, symbol);
        } else {
            positions = positionService.findByAccount(effectiveAccountId);
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
        // 平仓逻辑下沉到 TradingService.closePosition(REST 与 MCP 共用,DRY):
        // 查 position → 鉴权 → 派生 closeSide/effect → 构造 spot/perp command → submit。
        return ApiResponse.ok(tradingService.closePosition(positionId));
    }

    private PositionDto toDto(Position pos, Exchange exchange) {
        PositionEnrichment e = positionEnricher.enrich(pos, exchange);
        return new PositionDto(
                pos.getId(),
                pos.getAccountId(),
                pos.getSymbol(),
                pos.getSide(),
                pos.getQty(),
                pos.getAvgEntryPrice(),
                pos.getRealizedPnl(),
                e.unrealizedPnl(),
                e.currentPrice(),
                pos.getVersion(),
                pos.getLeverage(),
                pos.getMarginMode() != null ? pos.getMarginMode().name() : null,
                pos.getPositionSide(),
                pos.getLiquidationPrice(),
                pos.getMaintMargin(),
                pos.getFrozenAmount(),
                e.cumulativeFunding(),
                pos.getUpdatedAt());
    }
}
