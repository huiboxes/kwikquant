package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
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
public class OrderController {

    private final TradingService tradingService;
    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final ExchangeAccountService accountService;

    public OrderController(
            TradingService tradingService,
            OrderMapper orderMapper,
            FillMapper fillMapper,
            ExchangeAccountService accountService) {
        this.tradingService = tradingService;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderSubmitResult> submit(
            @RequestBody @Valid OrderSubmitRequest req, HttpServletRequest httpReq) {
        // Wave 8 §3.7 R4:Worker 请求由 WorkerTokenFilter 注入 (strategyId, userId, exchange) 到 request
        // attr;此时 request.accountId 应 null(Worker 不知),Controller 通过 token entry 推导 account,
        // 防越权(RUNNER token 只能操作其绑定的 strategy 对应 account)。
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
    public ApiResponse<OrderDetailDto> getOne(@PathVariable long orderId) {
        Order order = tradingService.getOrder(orderId);
        return ApiResponse.ok(toDto(order));
    }

    @GetMapping
    public ApiResponse<PageDto<OrderDetailDto>> list(@Valid OrderListQuery query) {
        // 鉴权：验证当前用户拥有该账户
        long currentUserId = SecurityUtils.currentUserId();
        accountService.getOwned(query.accountId(), currentUserId);

        List<OrderStatus> statuses = parseStatuses(query.status());
        int page = query.effectivePage();
        int pageSize = query.effectivePageSize();
        int offset = (page - 1) * pageSize;

        Instant startTime;
        Instant endTime;
        try {
            startTime = query.startTime() != null ? Instant.parse(query.startTime()) : null;
            endTime = query.endTime() != null ? Instant.parse(query.endTime()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.kwikquant.trading.domain.InvalidOrderException("Invalid date format: " + e.getMessage());
        }

        List<Order> orders = orderMapper.findByQuery(
                query.accountId(), query.symbol(), statuses, startTime, endTime, pageSize, offset);
        long total = orderMapper.countByQuery(query.accountId(), query.symbol(), statuses, startTime, endTime);

        List<OrderDetailDto> dtos = orders.stream().map(this::toDto).toList();
        return ApiResponse.ok(PageDto.of(dtos, page, pageSize, total));
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OrderCancelResult> cancel(@PathVariable long orderId) {
        OrderCancelResult result = tradingService.cancel(orderId);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{orderId}/fills")
    public ApiResponse<List<FillDto>> listFills(@PathVariable long orderId) {
        // 先校验订单归属（tradingService.getOrder 内含鉴权）
        tradingService.getOrder(orderId);
        List<Fill> fills = fillMapper.findByOrderId(orderId);
        List<FillDto> dtos = fills.stream().map(this::toFillDto).toList();
        return ApiResponse.ok(dtos);
    }

    private OrderSubmitCommand toCommand(OrderSubmitRequest req, long effectiveAccountId) {
        try {
            return new OrderSubmitCommand(
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
                order.getStatus() != null ? order.getStatus().name() : null,
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
