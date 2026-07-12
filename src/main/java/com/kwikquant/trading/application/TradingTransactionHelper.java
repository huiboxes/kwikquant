package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交易链路中需要独立事务的原子操作。从 {@link TradingService} 拆出，使 Spring AOP 代理能拦截
 * {@code @Transactional(REQUIRES_NEW)}——自调用（{@code this.method()}）绕过代理，事务注解不生效。
 *
 * <p>本类方法由 {@link TradingService}（跨 bean 调用，代理生效）和
 * {@link GtdExpirationScheduler}（已是跨 bean）调用。
 */
@Service
class TradingTransactionHelper {

    private final OrderMapper orderMapper;
    private final BalanceService balanceService;
    private final MarketDataService marketDataService;

    TradingTransactionHelper(
            OrderMapper orderMapper, BalanceService balanceService, MarketDataService marketDataService) {
        this.orderMapper = orderMapper;
        this.balanceService = balanceService;
        this.marketDataService = marketDataService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void insertOrder(Order order) {
        orderMapper.insert(order);
    }

    /**
     * 冻结挂单余额。BUY 冻结 quote = price*qty；SELL 冻结 base = qty。
     * 仅模拟盘真实冻结；真实交易所余额由交易所维护，noop。
     *
     * @throws InsufficientBalanceException 余额不足
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void freezeBalance(Order order, ExchangeAccount account) {
        if (!account.isPaperTrading()) return;
        String[] parts = order.getSymbol().split("/");
        if (parts.length != 2) {
            throw new InvalidOrderException("invalid symbol (expect BASE/QUOTE): " + order.getSymbol());
        }
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal freezePrice = order.getPrice();
            if (freezePrice == null) {
                Ticker ticker = marketDataService.getLatestTicker(
                        order.getExchange(), order.getMarketType(), order.getSymbol());
                if (ticker == null || ticker.last() == null) return;
                freezePrice = ticker.last();
            }
            BigDecimal amount = freezePrice.multiply(order.getAmount());
            balanceService.freeze(account.getId(), true, parts[1], amount);
            order.setFrozenQuoteAmount(amount);
            orderMapper.updateFrozenQuoteAmount(order.getId(), amount);
        } else {
            balanceService.freeze(account.getId(), true, parts[0], order.getAmount());
        }
    }

    /**
     * 解冻余额（撤单剩余 / executor 失败补偿）。仅模拟盘；真实交易所 noop。
     * BUY 用 {@link Order#getFrozenQuoteAmount()} 精确释放；无该字段的历史订单按价格估算。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void unfreezeBalance(Order order, ExchangeAccount account) {
        if (!account.isPaperTrading()) return;
        String[] parts = order.getSymbol().split("/");
        if (parts.length != 2) return;
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal amount = order.getFrozenQuoteAmount();
            if (amount == null) {
                BigDecimal freezePrice = order.getPrice();
                if (freezePrice == null) {
                    Ticker ticker = marketDataService.getLatestTicker(
                            order.getExchange(), order.getMarketType(), order.getSymbol());
                    if (ticker == null || ticker.last() == null) return;
                    freezePrice = ticker.last();
                }
                amount = freezePrice.multiply(order.remainingQty());
            }
            balanceService.unfreeze(account.getId(), true, parts[1], amount);
        } else {
            balanceService.unfreeze(account.getId(), true, parts[0], order.remainingQty());
        }
    }
}
