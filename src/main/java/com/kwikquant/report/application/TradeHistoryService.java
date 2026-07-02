package com.kwikquant.report.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TradeHistoryService {

    private static final List<OrderStatus> TERMINAL_STATUSES =
            List.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.EXPIRED);

    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final ExchangeAccountService accountService;

    public TradeHistoryService(OrderMapper orderMapper, FillMapper fillMapper, ExchangeAccountService accountService) {
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.accountService = accountService;
    }

    public PageDto<TradeHistoryItem> query(
            long userId, Long accountId, String symbol, Instant startTime, Instant endTime, int page, int pageSize) {

        List<Long> accountIds = resolveAccountIds(userId, accountId);
        List<TradeHistoryItem> allItems = new ArrayList<>();
        long totalCount = 0;
        int offset = (page - 1) * pageSize;

        for (long accId : accountIds) {
            long count = orderMapper.countByQuery(accId, symbol, TERMINAL_STATUSES, startTime, endTime);
            totalCount += count;

            List<Order> orders =
                    orderMapper.findByQuery(accId, symbol, TERMINAL_STATUSES, startTime, endTime, pageSize, offset);

            for (Order order : orders) {
                List<Fill> fills = fillMapper.findByOrderId(order.getId());
                BigDecimal totalFee = fills.stream().map(Fill::getFee).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalVolume = fills.stream()
                        .map(f -> f.getPrice().multiply(f.getQty()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                allItems.add(new TradeHistoryItem(
                        order.getId(),
                        accId,
                        order.getSymbol(),
                        order.getSide().name(),
                        order.getOrderType().name(),
                        order.getAmount(),
                        order.getFilledQty(),
                        order.getFilledAvgPrice(),
                        totalFee,
                        totalVolume,
                        order.getStatus().name(),
                        order.getCreatedAt(),
                        order.getUpdatedAt()));
            }
        }

        return PageDto.of(allItems, page, pageSize, totalCount);
    }

    public TradeHistoryStats stats(long userId, Long accountId, Instant since) {
        List<Long> accountIds = resolveAccountIds(userId, accountId);

        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        Instant effectiveSince = since != null ? since : Instant.EPOCH;

        for (long accId : accountIds) {
            List<Order> orders =
                    orderMapper.findByQuery(accId, null, List.of(OrderStatus.FILLED), effectiveSince, null, 10000, 0);
            for (Order order : orders) {
                List<Fill> fills = fillMapper.findByOrderId(order.getId());
                for (Fill fill : fills) {
                    totalVolume = totalVolume.add(fill.getPrice().multiply(fill.getQty()));
                    totalFees = totalFees.add(fill.getFee());
                }
            }
            realizedPnl = realizedPnl.add(fillMapper.sumNetCashflow(accId, effectiveSince));
        }

        return new TradeHistoryStats(totalVolume, totalFees, realizedPnl);
    }

    private List<Long> resolveAccountIds(long userId, Long accountId) {
        if (accountId != null) {
            accountService.getOwned(accountId, userId);
            return List.of(accountId);
        }
        return accountService.listByUser(userId).stream()
                .map(ExchangeAccountView::id)
                .toList();
    }

    public record TradeHistoryItem(
            long orderId,
            long accountId,
            String symbol,
            String side,
            String orderType,
            BigDecimal amount,
            BigDecimal filledQty,
            BigDecimal filledAvgPrice,
            BigDecimal totalFee,
            BigDecimal totalVolume,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record TradeHistoryStats(BigDecimal totalVolume, BigDecimal totalFees, BigDecimal realizedPnl) {}
}
