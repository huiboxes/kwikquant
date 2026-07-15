package com.kwikquant.report.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.application.VolumeAndFees;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.FillMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TradeHistoryService {

    private static final List<OrderStatus> TERMINAL_STATUSES =
            List.of(OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.EXPIRED);

    private final TradingService tradingService;
    private final ExchangeAccountService accountService;

    public TradeHistoryService(TradingService tradingService, ExchangeAccountService accountService) {
        this.tradingService = tradingService;
        this.accountService = accountService;
    }

    public PageDto<TradeHistoryItem> query(
            long userId, Long accountId, String symbol, Instant startTime, Instant endTime, int page, int pageSize) {

        List<Long> accountIds = resolveAccountIds(userId, accountId);
        int offset = (page - 1) * pageSize;

        // 跨账户统一分页：先汇总 totalCount，再对合并结果截断到 pageSize。
        long totalCount = 0;
        for (long accId : accountIds) {
            totalCount += tradingService.countOrders(accId, symbol, TERMINAL_STATUSES, startTime, endTime);
        }

        List<TradeHistoryItem> allItems = new ArrayList<>();
        if (accountIds.size() == 1) {
            long accId = accountIds.getFirst();
            List<Order> orders =
                    tradingService.queryOrders(accId, symbol, TERMINAL_STATUSES, startTime, endTime, pageSize, offset);
            // 批量查 fills 消除 N+1
            Map<Long, List<Fill>> fillsByOrder = batchLoadFills(orders);
            for (Order order : orders) {
                allItems.add(toItem(accId, order, fillsByOrder.getOrDefault(order.getId(), List.of())));
            }
        } else {
            List<Order> allOrders = new ArrayList<>();
            for (long accId : accountIds) {
                allOrders.addAll(tradingService.queryOrders(
                        accId, symbol, TERMINAL_STATUSES, startTime, endTime, (int) totalCount, 0));
            }
            // 批量查所有订单的 fills
            Map<Long, List<Fill>> fillsByOrder = batchLoadFills(allOrders);
            for (Order order : allOrders) {
                long accId = order.getAccountId();
                allItems.add(toItem(accId, order, fillsByOrder.getOrDefault(order.getId(), List.of())));
            }
            allItems.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            int end = Math.min(offset + pageSize, allItems.size());
            allItems = offset < allItems.size() ? allItems.subList(offset, end) : List.of();
        }

        return PageDto.of(allItems, page, pageSize, totalCount);
    }

    /** 批量加载订单的 fills，返回 orderId → fills 映射。单次 SQL 替代 N 次查询。 */
    private Map<Long, List<Fill>> batchLoadFills(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<Fill> allFills = tradingService.listFillsByOrders(orderIds);
        return allFills.stream().collect(Collectors.groupingBy(Fill::getOrderId));
    }

    private TradeHistoryItem toItem(long accId, Order order, List<Fill> fills) {
        BigDecimal totalFee = fills.stream().map(Fill::getFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVolume =
                fills.stream().map(f -> f.getPrice().multiply(f.getQty())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradeHistoryItem(
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
                order.getUpdatedAt());
    }

    public TradeHistoryStats stats(long userId, Long accountId, Instant since) {
        List<Long> accountIds = resolveAccountIds(userId, accountId);

        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;
        long totalDays = 0;
        long winDays = 0;

        Instant effectiveSince = since != null ? since : Instant.EPOCH;

        for (long accId : accountIds) {
            // 用聚合 SQL 替代 Java 层 N+1 循环
            VolumeAndFees vf = tradingService.sumVolumeAndFees(accId, effectiveSince);
            totalVolume = totalVolume.add(vf.totalVolume());
            totalFees = totalFees.add(vf.totalFees());
            realizedPnl = realizedPnl.add(tradingService.sumNetCashflow(accId, effectiveSince));

            FillMapper.DailyWinLossResult wl = tradingService.countDailyWinLoss(accId, effectiveSince);
            totalDays += wl.totalDays();
            winDays += wl.winDays();
        }

        BigDecimal winRate = totalDays > 0
                ? new BigDecimal(winDays).divide(new BigDecimal(totalDays), 4, RoundingMode.HALF_UP)
                : null;

        return new TradeHistoryStats(totalVolume, totalFees, realizedPnl, totalDays, winRate);
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

    public record TradeHistoryStats(
            BigDecimal totalVolume, BigDecimal totalFees, BigDecimal realizedPnl, long tradeCount, BigDecimal winRate) {}
}
