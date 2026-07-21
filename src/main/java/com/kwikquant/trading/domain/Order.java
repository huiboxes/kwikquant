package com.kwikquant.trading.domain;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 订单聚合根。封装订单数据 + OrderStatus 9 态状态机 + CAS version 字段。
 *
 * <p>状态推进通过 {@link #transitionTo(OrderStatus)} 进行；累积成交通过 {@link
 * #accumulateFill(BigDecimal, BigDecimal)}。状态机校验失败抛 {@link
 * IllegalOrderStateTransitionException}；overfill 抛 {@link MatchingException}。
 *
 * <p>本类是富领域对象但保留传统 getter/setter（保持与 Wave 1-3 现有实体风格一致，无 Lombok）。
 */
public class Order {

    private Long id;
    private long accountId;
    private String clientOrderId;
    private String exchangeOrderId;
    private String symbol;
    private MarketType marketType;
    /**
     * 下单账户当时选定的参考交易所，去规范化自 {@code ExchangeAccount.exchange}（下单时由 {@code
     * TradingService.submit} 写入）。撮合时 {@code PaperExecutor.onTicker} 用它过滤 ticker 来源，
     * 避免跨交易所串价。
     */
    private Exchange exchange;

    private OrderSide side;
    private OrderType orderType;
    private BigDecimal amount;
    private BigDecimal price;
    private BigDecimal stopPrice;
    private TimeInForce timeInForce;
    private Instant expireAt;
    private OrderStatus status;
    /**
     * 模拟盘 BUY 单挂单时冻结的 quote 金额（{@code TradingService.freezeBalance} 写入）。仅 BUY 单有值——
     * SELL 冻结的是 base 数量，没有价格漂移问题不需要这个字段。撤单/成交时用这个值精确解冻，而不是
     * 用撤单/成交时刻的价格重新算一遍（MARKET 单冻结价跟成交价系统性不同，重算会让 used 残留漂移）。
     */
    private BigDecimal frozenQuoteAmount;

    /** 合约杠杆倍数(PERP,1-125);SPOT null。§13 拍板。 */
    private Integer leverage;
    /** 合约保证金模式 ISOLATED/CROSS(PERP);SPOT null。§13 拍板。 */
    private MarginMode marginMode;
    /** 合约方向(OKX 四向 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT,PERP);SPOT null。§13 拍板。 */
    private PositionEffect positionEffect;

    private BigDecimal filledQty;
    private BigDecimal filledAvgPrice;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public Order() {}

    /**
     * 工厂方法：从 OrderSubmitCommand 创建 NEW 状态订单。
     *
     * <p>校验顺序：(1) 必填字段；(2) amount 精度 + 最小数量按 pairInfo；(3) price 与 orderType 一致性；(4) timeInForce
     * 与 expireAt 一致性（GTD 必须有 expireAt > now）。
     */
    public static Order create(OrderSubmitCommand cmd, TradingPairInfo pairInfo) {
        validate(cmd, pairInfo);

        Order o = new Order();
        o.accountId = cmd.accountId();
        o.clientOrderId = cmd.clientOrderId();
        o.symbol = cmd.symbol();
        o.marketType = cmd.marketType();
        o.side = cmd.side();
        o.orderType = cmd.orderType();
        o.amount = cmd.amount();
        o.price = cmd.price();
        o.stopPrice = cmd.stopPrice();
        o.timeInForce = cmd.timeInForce() != null ? cmd.timeInForce() : TimeInForce.GTC;
        o.expireAt = cmd.expireAt();
        o.status = OrderStatus.NEW;
        o.leverage = cmd.leverage();
        o.marginMode = cmd.marginMode();
        o.positionEffect = cmd.positionEffect();
        o.filledQty = BigDecimal.ZERO;
        o.filledAvgPrice = null;
        o.version = 0;
        return o;
    }

    private static void validate(OrderSubmitCommand cmd, TradingPairInfo pairInfo) {
        if (cmd == null) throw new InvalidOrderException("command is null");
        if (cmd.symbol() == null || cmd.symbol().isBlank()) {
            throw new InvalidOrderException("symbol is blank");
        }
        if (cmd.side() == null) throw new InvalidOrderException("side is required");
        if (cmd.orderType() == null) throw new InvalidOrderException("orderType is required");
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            throw new InvalidOrderException("amount must be positive");
        }
        if (pairInfo == null) {
            throw new InvalidOrderException("unknown symbol: " + cmd.symbol());
        }
        if (pairInfo.minQty() != null && cmd.amount().compareTo(pairInfo.minQty()) < 0) {
            throw new InvalidOrderException("amount " + cmd.amount() + " < minQty " + pairInfo.minQty());
        }
        // 精度：amount 必须按 stepSize 对齐
        if (pairInfo.stepSize() != null && pairInfo.stepSize().signum() > 0) {
            BigDecimal mod = cmd.amount().remainder(pairInfo.stepSize());
            if (mod.signum() != 0) {
                throw new InvalidOrderException(
                        "amount " + cmd.amount() + " not aligned to stepSize " + pairInfo.stepSize());
            }
        }
        // price 一致性
        boolean needsPrice = cmd.orderType() == OrderType.LIMIT
                || cmd.orderType() == OrderType.STOP_LIMIT
                || cmd.orderType() == OrderType.TAKE_PROFIT_LIMIT;
        if (needsPrice && (cmd.price() == null || cmd.price().signum() <= 0)) {
            throw new InvalidOrderException("price required for " + cmd.orderType());
        }
        boolean needsStopPrice = cmd.orderType() == OrderType.STOP_MARKET
                || cmd.orderType() == OrderType.STOP_LIMIT
                || cmd.orderType() == OrderType.TAKE_PROFIT_MARKET
                || cmd.orderType() == OrderType.TAKE_PROFIT_LIMIT;
        if (needsStopPrice && (cmd.stopPrice() == null || cmd.stopPrice().signum() <= 0)) {
            throw new InvalidOrderException("stopPrice required for " + cmd.orderType());
        }
        // price 精度（tickSize）
        if (cmd.price() != null
                && pairInfo.tickSize() != null
                && pairInfo.tickSize().signum() > 0) {
            BigDecimal mod = cmd.price().remainder(pairInfo.tickSize());
            if (mod.signum() != 0) {
                throw new InvalidOrderException(
                        "price " + cmd.price() + " not aligned to tickSize " + pairInfo.tickSize());
            }
        }
        // stopPrice 精度（tickSize）
        if (cmd.stopPrice() != null
                && pairInfo.tickSize() != null
                && pairInfo.tickSize().signum() > 0) {
            BigDecimal mod = cmd.stopPrice().remainder(pairInfo.tickSize());
            if (mod.signum() != 0) {
                throw new InvalidOrderException(
                        "stopPrice " + cmd.stopPrice() + " not aligned to tickSize " + pairInfo.tickSize());
            }
        }
        // GTD 必须有 expireAt > now
        if (cmd.timeInForce() == TimeInForce.GTD) {
            if (cmd.expireAt() == null) {
                throw new InvalidOrderException("expireAt required for GTD orders");
            }
            if (!cmd.expireAt().isAfter(Instant.now())) {
                throw new InvalidOrderException("expireAt must be in the future");
            }
        }
        // PERP 合约校验(§13 拍板 + §10 B5 + §12 M2-s)
        if (cmd.marketType() == MarketType.PERP) {
            if (cmd.leverage() == null || cmd.leverage() < 1 || cmd.leverage() > 125) {
                throw new InvalidOrderException("PERP leverage must be 1-125, got: " + cmd.leverage());
            }
            if (cmd.marginMode() == null) {
                throw new InvalidOrderException("PERP marginMode required (ISOLATED/CROSS)");
            }
            if (cmd.positionEffect() == null) {
                throw new InvalidOrderException(
                        "PERP positionEffect required (OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)");
            }
            // maxLeverage by symbol(§12 M2-s):依赖 TradingPairInfo.maxLeverage 字段(§10 m4,阶段3 补)。
            // 当前 pairInfo 无 maxLeverage,留账 TODO: pairInfo.maxLeverage()!=null && leverage>max → reject
        } else {
            // SPOT 不允许合约字段(§10 B5)
            if (cmd.leverage() != null || cmd.marginMode() != null || cmd.positionEffect() != null) {
                throw new InvalidOrderException("SPOT order must not set leverage/marginMode/positionEffect");
            }
        }
    }

    /** 状态推进。违反状态机抛异常。<strong>仅更新内存对象，DB 写入由 ExecutionService 事务内完成。</strong> */
    public void transitionTo(OrderStatus target) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalOrderStateTransitionException(status, target);
        }
        this.status = target;
    }

    /**
     * 累积部分成交：更新 filledQty 和 filledAvgPrice（加权平均）。
     *
     * <p>不更新 status——由调用方决定推进到 PARTIALLY_FILLED 还是 FILLED。
     */
    public void accumulateFill(BigDecimal fillQty, BigDecimal fillPrice) {
        if (fillQty == null || fillQty.signum() <= 0) {
            throw new MatchingException("fillQty must be positive, got: " + fillQty);
        }
        if (fillPrice == null || fillPrice.signum() <= 0) {
            throw new MatchingException("fillPrice must be positive, got: " + fillPrice);
        }
        BigDecimal newFilledQty = this.filledQty.add(fillQty);
        if (newFilledQty.compareTo(this.amount) > 0) {
            throw new MatchingException("over-fill: filledQty=" + newFilledQty + " > amount=" + this.amount);
        }
        BigDecimal newAvgPrice;
        if (this.filledAvgPrice == null) {
            newAvgPrice = fillPrice;
        } else {
            BigDecimal totalCost = this.filledAvgPrice.multiply(this.filledQty).add(fillPrice.multiply(fillQty));
            newAvgPrice = totalCost.divide(newFilledQty, 8, RoundingMode.HALF_UP);
        }
        this.filledQty = newFilledQty;
        this.filledAvgPrice = newAvgPrice;
    }

    /** 剩余数量 = amount - filledQty。 */
    public BigDecimal remainingQty() {
        return amount.subtract(filledQty);
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public String getExchangeOrderId() {
        return exchangeOrderId;
    }

    public void setExchangeOrderId(String exchangeOrderId) {
        this.exchangeOrderId = exchangeOrderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public MarketType getMarketType() {
        return marketType;
    }

    public void setMarketType(MarketType marketType) {
        this.marketType = marketType;
    }

    public Exchange getExchange() {
        return exchange;
    }

    public void setExchange(Exchange exchange) {
        this.exchange = exchange;
    }

    public BigDecimal getFrozenQuoteAmount() {
        return frozenQuoteAmount;
    }

    public void setFrozenQuoteAmount(BigDecimal frozenQuoteAmount) {
        this.frozenQuoteAmount = frozenQuoteAmount;
    }

    public Integer getLeverage() {
        return leverage;
    }

    public void setLeverage(Integer leverage) {
        this.leverage = leverage;
    }

    public MarginMode getMarginMode() {
        return marginMode;
    }

    public void setMarginMode(MarginMode marginMode) {
        this.marginMode = marginMode;
    }

    public PositionEffect getPositionEffect() {
        return positionEffect;
    }

    public void setPositionEffect(PositionEffect positionEffect) {
        this.positionEffect = positionEffect;
    }

    /**
     * reduceOnly 纯派生(§13 拍板 3):CLOSE_* 自动 true(平仓自动 reduceOnly,前端不显式传);
     * SPOT 或 OPEN_* 返 false。无字段无 setter,MyBatis 不映射。
     */
    public boolean isReduceOnly() {
        return positionEffect != null && positionEffect.name().startsWith("CLOSE_");
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(BigDecimal stopPrice) {
        this.stopPrice = stopPrice;
    }

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public void setTimeInForce(TimeInForce timeInForce) {
        this.timeInForce = timeInForce;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getFilledQty() {
        return filledQty;
    }

    public void setFilledQty(BigDecimal filledQty) {
        this.filledQty = filledQty;
    }

    public BigDecimal getFilledAvgPrice() {
        return filledAvgPrice;
    }

    public void setFilledAvgPrice(BigDecimal filledAvgPrice) {
        this.filledAvgPrice = filledAvgPrice;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
