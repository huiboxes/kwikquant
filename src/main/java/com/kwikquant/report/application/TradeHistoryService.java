package com.kwikquant.report.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        // 对每个账户各自用相同的 offset+limit 查询再拼接是错误的（返回条目数 = N*pageSize，
        // 翻页数据重复/遗漏）。正确做法是对每个账户查出一个较宽的窗口，合并后在应用层分页。
        // 当前简化实现：单账户走数据库分页（高效），多账户走应用层分页（多账户场景不多且数据量小）。
        long totalCount = 0;
        for (long accId : accountIds) {
            totalCount += tradingService.countOrders(accId, symbol, TERMINAL_STATUSES, startTime, endTime);
        }

        List<TradeHistoryItem> allItems = new ArrayList<>();
        if (accountIds.size() == 1) {
            long accId = accountIds.getFirst();
            List<Order> orders =
                    tradingService.queryOrders(accId, symbol, TERMINAL_STATUSES, startTime, endTime, pageSize, offset);
            for (Order order : orders) {
                allItems.add(toItem(accId, order));
            }
        } else {
            for (long accId : accountIds) {
                List<Order> orders = tradingService.queryOrders(
                        accId, symbol, TERMINAL_STATUSES, startTime, endTime, (int) totalCount, 0);
                for (Order order : orders) {
                    allItems.add(toItem(accId, order));
                }
            }
            allItems.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            int end = Math.min(offset + pageSize, allItems.size());
            allItems = offset < allItems.size() ? allItems.subList(offset, end) : List.of();
        }

        return PageDto.of(allItems, page, pageSize, totalCount);
    }

    private TradeHistoryItem toItem(long accId, Order order) {
        List<Fill> fills = tradingService.listFillsByOrder(order.getId());
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

        Instant effectiveSince = since != null ? since : Instant.EPOCH;

        for (long accId : accountIds) {
            List<Order> orders = tradingService.queryOrders(
                    accId, null, List.of(OrderStatus.FILLED), effectiveSince, null, 10000, 0);
            for (Order order : orders) {
                List<Fill> fills = tradingService.listFillsByOrder(order.getId());
                for (Fill fill : fills) {
                    totalVolume = totalVolume.add(fill.getPrice().multiply(fill.getQty()));
                    totalFees = totalFees.add(fill.getFee());
                }
            }
            realizedPnl = realizedPnl.add(tradingService.sumNetCashflow(accId, effectiveSince));
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
