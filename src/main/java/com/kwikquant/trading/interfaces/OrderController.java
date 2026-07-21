package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单 REST API。
 *
 * <p>端点：POST /api/v1/orders（下单）、GET /api/v1/orders/{orderId}（查询）、GET /api/v1/orders（列表）、DELETE
 * /api/v1/orders/{orderId}（撤单）、GET /api/v1/orders/{orderId}/fills（成交记录）。
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "订单交易")
public class OrderController {

    private final TradingService tradingService;
    private final ExchangeAccountService accountService;

    public OrderController(TradingService tradingService, ExchangeAccountService accountService) {
        this.tradingService = tradingService;
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "提交订单",
            description = "双通道鉴权——用户请求：JWT + body.accountId 必填（后端校验账户归属）；"
                    + "Worker 请求：X-Worker-Token + body.accountId 应为空（后端据 token 推导）。"
                    + "风控拒绝时 HTTP 200 + code=4105（业务结果，非错误）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "风控拒绝（code=4105 ORDER_RISK_REJECTED，HTTP 200 是业务结果非错误）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "订单状态不可转移（4101）或余额不足（4102）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "订单参数非法（4103 ORDER_INVALID_PARAMS）")
    public ApiResponse<OrderSubmitResult> submit(
            @RequestBody @Valid OrderSubmitRequest req, HttpServletRequest httpReq) {
        // Wave 8 §3.7 R4:Worker 请求由 WorkerTokenFilter 注入 (strategyId, userId, exchange) 到 request
        // attr;此时 request.accountId 应 null(Worker 不知),Controller 通过 token entry 推导 account,
        // 防越权(RUNNER token 只能操作其绑定的 strategy 对应 account)。
        // FE-TD-038:findByUserAndExchange 返单账户无歧义,依赖 exchange_accounts UNIQUE(user_id, exchange) 不变量。
        Long workerStrategyId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR);
        Long effectiveAccountId = req.accountId();
        if (workerStrategyId != null) {
            Long workerUserId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_USER_ID_ATTR);
            String workerExchange = (String) httpReq.getAttribute(WorkerTokenFilter.WORKER_EXCHANGE_ATTR);
            ExchangeAccount derived = accountService.findByUserAndExchange(workerUserId, workerExchange);
            if (derived == null) {
                throw new com.kwikquant.trading.domain.InvalidOrderException(
                        "no exchange account for user=" + workerUserId + " exchange=" + workerExchange);
            }
            effectiveAccountId = derived.getId();
        } else if (effectiveAccountId == null) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("accountId required for user requests");
        }
        OrderSubmitCommand cmd = toCommand(req, effectiveAccountId);
        OrderSubmitResult result = tradingService.submit(cmd);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "查订单详情", description = "需 JWT 鉴权。订单不存在返回 404（4001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "订单不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND）")
    public ApiResponse<OrderDetailDto> getOne(@PathVariable long orderId) {
        Order order = tradingService.getOrder(orderId);
        return ApiResponse.ok(toDto(order));
    }

    @GetMapping
    @Operation(
            summary = "分页查询订单",
            description = "需 JWT 鉴权。按账户 + 可选 symbol/status/时间范围过滤。accountId 鉴权校验归属，越权返回 403（1002）。"
                    + "日期格式非法或 status 枚举非法返回 400（4103）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "参数非法（4103 ORDER_INVALID_PARAMS：日期格式/status 枚举非法）")
    public ApiResponse<PageDto<OrderDetailDto>> list(@Valid OrderListQuery query) {
        // 鉴权：验证当前用户拥有该账户
        long currentUserId = SecurityUtils.currentUserId();
        accountService.getOwned(query.accountId(), currentUserId);

        List<OrderStatus> statuses = parseStatuses(query.status());
        PageQuery pq = PageQuery.ofLarge(query.page(), query.pageSize());

        Instant startTime;
        Instant endTime;
        try {
            startTime = query.startTime() != null ? Instant.parse(query.startTime()) : null;
            endTime = query.endTime() != null ? Instant.parse(query.endTime()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("Invalid date format: " + e.getMessage());
        }

        List<Order> orders = tradingService.queryOrders(
                query.accountId(), query.symbol(), statuses, startTime, endTime, pq.pageSize(), pq.offset());
        long total = tradingService.countOrders(query.accountId(), query.symbol(), statuses, startTime, endTime);

        List<OrderDetailDto> dtos = orders.stream().map(this::toDto).toList();
        return ApiResponse.ok(PageDto.of(dtos, pq.page(), pq.pageSize(), total));
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "撤单",
            description = "需 JWT 鉴权。返回 202 ACCEPTED + OrderCancelResult。" + "订单已成交/不可撤返回 422（4101）；并发版本冲突返回 409（4107）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "订单状态不可撤，如已 FILLED（4101 ORDER_ILLEGAL_STATE_TRANSITION）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "并发版本冲突（4107 ORDER_CONCURRENCY_CONFLICT）")
    public ApiResponse<OrderCancelResult> cancel(@PathVariable long orderId) {
        OrderCancelResult result = tradingService.cancel(orderId);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{orderId}/fills")
    @Operation(summary = "查成交记录", description = "需 JWT 鉴权。按 orderId 返回成交明细列表，含 taker/maker 标识。订单不存在返回 404（4001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "订单不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND）")
    public ApiResponse<List<FillDto>> listFills(@PathVariable long orderId) {
        // 先校验订单归属（tradingService.getOrder 内含鉴权）
        tradingService.getOrder(orderId);
        List<Fill> fills = tradingService.listFillsByOrder(orderId);
        List<FillDto> dtos = fills.stream().map(this::toFillDto).toList();
        return ApiResponse.ok(dtos);
    }

    private OrderSubmitCommand toCommand(OrderSubmitRequest req, long effectiveAccountId) {
        try {
            return OrderSubmitCommand.spot(
                    effectiveAccountId,
                    req.symbol(),
                    MarketType.valueOf(req.marketType().toUpperCase()),
                    OrderSide.valueOf(req.side().toUpperCase()),
                    OrderType.valueOf(req.orderType().toUpperCase()),
                    req.amount(),
                    req.price(),
                    req.stopPrice(),
                    req.timeInForce() != null
                            ? TimeInForce.valueOf(req.timeInForce().toUpperCase())
                            : TimeInForce.GTC,
                    req.expireAt() != null ? Instant.parse(req.expireAt()) : null,
                    req.clientOrderId());
        } catch (IllegalArgumentException e) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("Invalid enum value: " + e.getMessage());
        }
    }

    private OrderDetailDto toDto(Order order) {
        return new OrderDetailDto(
                order.getId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getMarketType() != null ? order.getMarketType().name() : null,
                order.getSide() != null ? order.getSide().name().toLowerCase() : null,
                order.getOrderType() != null ? order.getOrderType().name().toLowerCase() : null,
                order.getAmount(),
                order.getPrice(),
                order.getStopPrice(),
                order.getTimeInForce() != null ? order.getTimeInForce().name() : null,
                order.getExpireAt(),
                order.getStatus(),
                order.getFilledQty(),
                order.getFilledAvgPrice(),
                order.getClientOrderId(),
                order.getExchangeOrderId(),
                order.getVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private FillDto toFillDto(Fill fill) {
        return new FillDto(
                fill.getId(),
                fill.getOrderId(),
                fill.getAccountId(),
                fill.getSymbol(),
                fill.getSide() != null ? fill.getSide().name().toLowerCase() : null,
                fill.getPrice(),
                fill.getQty(),
                fill.getFee(),
                fill.getFeeCurrency(),
                fill.getLiquidity(),
                fill.getExternalFillId(),
                fill.getFilledAt());
    }

    private List<OrderStatus> parseStatuses(String statusParam) {
        if (statusParam == null || statusParam.isBlank()) return null;
        try {
            return Arrays.stream(statusParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> OrderStatus.valueOf(s.toUpperCase()))
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("Invalid status value: " + e.getMessage());
        }
    }
}
