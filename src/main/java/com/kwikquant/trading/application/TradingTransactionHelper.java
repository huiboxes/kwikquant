package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.InsufficientMarginException;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * 冻结挂单余额。按 {@code (marketType, positionEffect)} 分支(§3.1 / §13 拍板 1 / M1-impl):
     * <ul>
     *   <li>{@code SPOT BUY}:冻 quote = price*qty(现状不变)</li>
     *   <li>{@code SPOT SELL}:冻 base = qty(现状不变)</li>
     *   <li>{@code PERP OPEN_LONG / OPEN_SHORT}:冻 quote initialMargin = qty*price/leverage(逐仓,
     *       不区分多空方向)。余额不足抛 {@link InsufficientMarginException}(extends
     *       InsufficientBalanceException,复用 ErrorCode,便于日志/审计区分场景)</li>
     *   <li>{@code PERP CLOSE_*}:reduceOnly 平仓,不冻保证金,直接 return</li>
     * </ul>
     * 仅模拟盘真实冻结;真实交易所余额由交易所维护,noop。
     *
     * @param marketPrice MARKET BUY 时预获取的价格(来自 submit 中统一查询),避免重复查 ticker (TD-013)。
     *     为 null 时回退到内部查询。PERP MARKET 单同 SPOT MARKET BUY 共用此参数。
     * @throws InsufficientBalanceException SPOT 余额不足
     * @throws InsufficientMarginException PERP 保证金不足(initialMargin &gt; 可用 quote)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void freezeBalance(Order order, ExchangeAccount account, BigDecimal marketPrice) {
        if (!account.isPaperTrading()) return;
        if (order.getMarketType() == MarketType.PERP) {
            freezePerpMargin(order, account, marketPrice);
            return;
        }
        freezeSpot(order, account, marketPrice);
    }

    /** SPOT 挂单冻结(现状逐字保留,仅抽出便于 PERP 分支对照)。 */
    private void freezeSpot(Order order, ExchangeAccount account, BigDecimal marketPrice) {
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
     * PERP 挂单冻结保证金(§3.1)。OPEN_LONG/OPEN_SHORT 冻 quote initialMargin = qty*price/leverage,
     * 写入 {@link Order#setFrozenQuoteAmount} 供后续 unfreeze 按比例释放(同 SPOT BUY 口径)。
     * CLOSE_* reduceOnly 不冻,直接 return(无 frozenQuoteAmount,unfreeze 也跳过)。
     *
     * @throws InsufficientMarginException initialMargin 超过账户可用 quote 余额
     */
    private void freezePerpMargin(Order order, ExchangeAccount account, BigDecimal marketPrice) {
        PositionEffect effect = order.getPositionEffect();
        if (effect == PositionEffect.CLOSE_LONG || effect == PositionEffect.CLOSE_SHORT) {
            // reduceOnly 平仓:不冻保证金,成交时释放对应持仓的 frozenAmount(applyPerpDelta)
            return;
        }
        if (effect != PositionEffect.OPEN_LONG && effect != PositionEffect.OPEN_SHORT) {
            // 防御性:Order.validate 已保证非 null 且四值之一,此处二次保险
            throw new InvalidOrderException("PERP order requires valid positionEffect, got: " + effect);
        }
        String quoteCurrency = splitQuoteCurrency(order.getSymbol());
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
        int leverage = order.getLeverage();
        if (leverage <= 0) {
            // 防御性:Order.validate 保证 1-125
            throw new InvalidOrderException("PERP leverage must be positive, got: " + leverage);
        }
        BigDecimal notional = freezePrice.multiply(order.getAmount());
        // initialMargin = notional / leverage,8 位精度足够(OKX BTC 1 contracts × 40000 / 125 = 320.0)
        BigDecimal initialMargin = notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
        try {
            balanceService.freeze(account.getId(), true, quoteCurrency, initialMargin);
        } catch (InsufficientBalanceException e) {
            // PERP 场景重抛 InsufficientMarginException 便于日志/审计区分(ErrorCode 复用,HTTP 仍 4102)
            throw new InsufficientMarginException(
                    "insufficient margin for PERP " + effect + ": initialMargin=" + initialMargin + " " + quoteCurrency
                            + " required, but " + e.getMessage(),
                    e);
        }
        order.setFrozenQuoteAmount(initialMargin);
        orderMapper.updateFrozenQuoteAmount(order.getId(), initialMargin);
    }

    /** 从 BASE/QUOTE 拆出 quote 货币;非法 symbol 抛 InvalidOrderException。 */
    private static String splitQuoteCurrency(String symbol) {
        String[] parts = symbol.split("/");
        if (parts.length != 2) {
            throw new InvalidOrderException("invalid symbol (expect BASE/QUOTE): " + symbol);
        }
        return parts[1];
    }

    /**
     * 解冻余额（撤单剩余 / executor 失败补偿）。仅模拟盘；真实交易所 noop。
     * 按 {@code (marketType, positionEffect)} 分支:
     * <ul>
     *   <li>{@code SPOT BUY}:用 {@link Order#getFrozenQuoteAmount()} 精确释放,无则按价格估算(现状)</li>
     *   <li>{@code SPOT SELL}:释放 base qty(现状)</li>
     *   <li>{@code PERP OPEN_LONG / OPEN_SHORT}:解冻 frozenQuoteAmount(下单时存的 initialMargin),
     *       按剩余未成交比例折算(同 SPOT BUY 口径)</li>
     *   <li>{@code PERP CLOSE_*}:reduceOnly 平仓未冻保证金,直接 return</li>
     * </ul>
     *
     * <p>{@code frozenQuoteAmount} 是下单时冻结的原始全额，订单生命周期内不会随部分成交递减
     * （递减发生在 {@link BalanceService} 的 {@code used} 余额桶，而非 order 记录本身）。因此这里必须
     * 按{@link Order#remainingQty() 剩余未成交比例}折算后再解冻，否则已成交部分对应的冻结额会被
     * 重复释放，虚增可用余额（{@link ExecutionService#computeProportionalFrozen} 是同一折算口径）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void unfreezeBalance(Order order, ExchangeAccount account) {
        if (!account.isPaperTrading()) return;
        if (order.getMarketType() == MarketType.PERP) {
            unfreezePerpMargin(order, account);
            return;
        }
        unfreezeSpot(order, account);
    }

    /** SPOT 解冻(现状逐字保留,仅抽出便于 PERP 分支对照)。 */
    private void unfreezeSpot(Order order, ExchangeAccount account) {
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

    /**
     * PERP 解冻保证金。OPEN_* 单:解冻 frozenQuoteAmount 按剩余比例折算;CLOSE_* 单:
     * freezeBalance 未冻(无 frozenQuoteAmount),直接 return。
     */
    private void unfreezePerpMargin(Order order, ExchangeAccount account) {
        PositionEffect effect = order.getPositionEffect();
        if (effect == PositionEffect.CLOSE_LONG || effect == PositionEffect.CLOSE_SHORT) {
            // reduceOnly 平仓未冻保证金,无解冻
            return;
        }
        BigDecimal initialMargin = order.getFrozenQuoteAmount();
        if (initialMargin == null) {
            // 防御性:OPEN_* 单 freezeBalance 必设 frozenQuoteAmount,此处兜底日志(不抛,避免解冻链路崩)
            log.warn(
                    "[trading] PERP unfreeze skipped: no frozenQuoteAmount for orderId={} effect={} —"
                            + " frozen margin may leak until manual reset",
                    order.getId(),
                    effect);
            return;
        }
        String quoteCurrency = splitQuoteCurrency(order.getSymbol());
        BigDecimal amount =
                ExecutionService.computeProportionalFrozen(initialMargin, order.remainingQty(), order.getAmount());
        balanceService.unfreeze(account.getId(), true, quoteCurrency, amount);
    }
}
