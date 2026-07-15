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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TradingTransactionHelper.class);

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
     * @param marketPrice MARKET BUY 时预获取的价格（来自 submit 中统一查询），避免重复查 ticker (TD-013)。
     *     为 null 时回退到内部查询。
     * @throws InsufficientBalanceException 余额不足
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void freezeBalance(Order order, ExchangeAccount account, BigDecimal marketPrice) {
        if (!account.isPaperTrading()) return;
        String[] parts = order.getSymbol().split("/");
        if (parts.length != 2) {
            throw new InvalidOrderException("invalid symbol (expect BASE/QUOTE): " + order.getSymbol());
        }
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal freezePrice = order.getPrice();
            if (freezePrice == null) {
                freezePrice = marketPrice;
                if (freezePrice == null) {
                    Ticker ticker = marketDataService.getLatestTicker(
                            order.getExchange(), order.getMarketType(), order.getSymbol());
                    if (ticker == null || ticker.last() == null) {
                        throw new InvalidOrderException(
                                "no ticker available to estimate MARKET order cost for " + order.getSymbol());
                    }
                    freezePrice = ticker.last();
                }
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
     *
     * <p>{@code frozenQuoteAmount} 是下单时冻结的原始全额，订单生命周期内不会随部分成交递减
     * （递减发生在 {@link BalanceService} 的 {@code used} 余额桶，而非 order 记录本身）。因此这里必须
     * 按{@link Order#remainingQty() 剩余未成交比例}折算后再解冻，否则已成交部分对应的冻结额会被
     * 重复释放，虚增可用余额（{@link ExecutionService#computeProportionalFrozen} 是同一折算口径）。
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
                    if (ticker == null || ticker.last() == null) {
                        log.warn(
                                "[trading] unfreeze skipped: no ticker and no frozenQuoteAmount for orderId={} symbol={}"
                                        + " — frozen balance may leak until manual reset",
                                order.getId(),
                                order.getSymbol());
                        return;
                    }
                    freezePrice = ticker.last();
                }
                amount = freezePrice.multiply(order.remainingQty());
            } else {
                amount = ExecutionService.computeProportionalFrozen(amount, order.remainingQty(), order.getAmount());
            }
            balanceService.unfreeze(account.getId(), true, parts[1], amount);
        } else {
            balanceService.unfreeze(account.getId(), true, parts[0], order.remainingQty());
        }
    }
}
