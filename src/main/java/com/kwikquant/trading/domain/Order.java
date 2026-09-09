package com.kwikquant.trading.domain;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderAcceptance;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PairSpec;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 订单聚合根。封装订单数据 + OrderStatus 状态机 + CAS version 字段。PENDING_NEW 同时表示下单请求已发送但结果未知，
 * 可通过交易所 client order ID 对账恢复。
 *
 * <p>状态推进通过 {@link #transitionTo(OrderStatus)} 进行；累积成交通过 {@link
 * #accumulateFill(BigDecimal, BigDecimal)}。状态机校验失败抛 {@link
 * IllegalOrderStateTransitionException}；overfill 抛 {@link MatchingException}。
 *
 * <p>本类是富领域对象但保留传统 getter/setter(无 Lombok,与项目既有实体风格一致)。
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

    /**
     * 合约杠杆倍数(PERP,1-{@value com.kwikquant.shared.types.OrderAcceptance#MAX_LEVERAGE_CAP}
     * 且不超交易所 per-symbol 声明);SPOT null。
     */
    private Integer leverage;
    /** 合约保证金模式 ISOLATED/CROSS(PERP);SPOT null。 */
    private MarginMode marginMode;
    /** 合约方向(OKX 四向 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT,PERP);SPOT null。 */
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
     * <p>校验：静态接受性走 {@link OrderAcceptance} 19 条规则（docs/matching-spec.md §9.2，
     * 顺序敏感，与 Python acceptance.py 差分对拍）；墙钟相关的 GTD 校验（expireAt > now）
     * 不进对拍层，规则表之后单独执行（§9.1）。
     */
    public static Order create(OrderSubmitCommand cmd, TradingPairInfo pairInfo) {
        validate(cmd, pairInfo);

        Order o = new Order();
        o.accountId = cmd.accountId();
        o.clientOrderId = cmd.clientOrderId();
        o.symbol = cmd.symbol();
        o.marketType = cmd.marketType();
        // PERP side 单源=positionEffect 派生(validate 已保证显式 side 与派生值一致);SPOT 用传入 side
        o.side = cmd.marketType() == MarketType.PERP ? cmd.positionEffect().toSide() : cmd.side();
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

    /**
     * 系统强平订单工厂(processLiquidation 五步之"Order FILLED"步骤)。
     *
     * <p>绕过 {@link #create} 的 {@code validate} + 状态机:系统强平不是用户提交,markPrice(触发价)
     * 可能不满足 tickSize 精度校验,且系统 order 不走 NEW→PENDING_NEW→SUBMITTED 用户提交流程,直接
     * {@code status=FILLED} 终态。这是系统操作特例,非用户 order 状态机破坏。
     *
     * <p>字段从被强平的 {@link Position} + {@code markPrice} + 派生 {@link PositionEffect}(平多=CLOSE_LONG
     * / 平空=CLOSE_SHORT)构造:{@code side} 派生(CLOSE_LONG→SELL / CLOSE_SHORT→BUY),{@code amount/qty}
     * = position.qty,{@code price/filledAvgPrice} = markPrice,{@code leverage/marginMode} 镜像 position,
     * {@code orderType}=MARKET(系统强平按市价成交),{@code timeInForce}=GTC。
     *
     * <p>该 Order 是"已完成的历史记录",不进 activeOrders、不被撮合、不走 WS 订单状态广播——
     * 它的存在只为给 {@code Fill.order_id}(NOT NULL 约束)提供来源 + 审计可追溯成交链。
     *
     * @param position  被强平的持仓(必须非 flat,有 qty/avgEntryPrice/leverage/marginMode/positionSide)
     * @param exchange  下单账户交易所(从 ExchangeAccount.getExchange() 拿,Position 无 exchange 字段)
     * @param markPrice 触发强平的标记价(也是成交价)
     * @param effect   派生的平仓方向(CLOSE_LONG 平多 / CLOSE_SHORT 平空)
     */
    public static Order createLiquidation(
            Position position, Exchange exchange, BigDecimal markPrice, PositionEffect effect) {
        Order o = new Order();
        o.accountId = position.getAccountId();
        o.clientOrderId = null;
        o.exchangeOrderId = null;
        o.symbol = position.getSymbol();
        o.exchange = exchange;
        o.marketType = MarketType.PERP;
        o.side = effect.toSide();
        o.orderType = OrderType.MARKET;
        o.amount = position.getQty();
        o.price = markPrice;
        o.stopPrice = null;
        o.timeInForce = TimeInForce.GTC;
        o.expireAt = null;
        o.status = OrderStatus.FILLED;
        o.leverage = position.getLeverage();
        o.marginMode = position.getMarginMode();
        o.positionEffect = effect;
        o.filledQty = position.getQty();
        o.filledAvgPrice = markPrice;
        o.frozenQuoteAmount = null;
        o.version = 0;
        return o;
    }

    /**
     * 静态校验委托 {@link OrderAcceptance} 纯函数层(docs/matching-spec.md §9,与 Python 回测侧
     * acceptance.py 差分对拍),拒时按 result.message 抛 {@link InvalidOrderException}。存量 19 条
     * 消息与委托重构前逐字一致;行为差异均在 spec §9 显式声明(杠杆上限 125→100、新增 MAX_QTY、
     * PERP 缺 leverage 从默认值改 fail-closed 拒)。墙钟相关的 GTD 校验不进对拍层(spec §9.1),
     * 保留在本方法(规则表之后执行,多重违规时命中消息以 §9.1 顺序为准)。
     */
    private static void validate(OrderSubmitCommand cmd, TradingPairInfo pairInfo) {
        if (cmd == null) throw new InvalidOrderException("command is null");
        OrderAcceptance.AcceptResult result = OrderAcceptance.check(acceptanceInput(cmd), pairSpec(pairInfo));
        if (!result.ok()) {
            throw new InvalidOrderException(result.message());
        }
        // GTD 必须有 expireAt > now(依赖墙钟,不进接受性纯函数层)
        if (cmd.timeInForce() == TimeInForce.GTD) {
            if (cmd.expireAt() == null) {
                throw new InvalidOrderException("expireAt required for GTD orders");
            }
            if (!cmd.expireAt().isAfter(Instant.now())) {
                throw new InvalidOrderException("expireAt must be in the future");
            }
        }
    }

    private static OrderAcceptance.Input acceptanceInput(OrderSubmitCommand cmd) {
        return new OrderAcceptance.Input(
                cmd.symbol(),
                cmd.marketType(),
                cmd.side(),
                cmd.orderType(),
                cmd.amount(),
                cmd.price(),
                cmd.stopPrice(),
                cmd.leverage(),
                cmd.marginMode(),
                cmd.positionEffect());
    }

    /** {@link TradingPairInfo}(market) → {@link PairSpec}(shared) 子集转换(shared 不能反向依赖 market)。 */
    private static PairSpec pairSpec(TradingPairInfo pairInfo) {
        if (pairInfo == null) {
            return null;
        }
        return new PairSpec(
                pairInfo.symbol(),
                pairInfo.marketType(),
                pairInfo.minQty(),
                pairInfo.maxQty(),
                pairInfo.tickSize(),
                pairInfo.stepSize(),
                pairInfo.maxLeverage());
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

    /**
     * 按本次成交量占订单总量比例计算应解冻的 frozenQuoteAmount(避免每次 fill 释放整单冻结额
     * 致 used 多减)。逐笔独立 8 位 HALF_UP 舍入,Σ逐笔与冻结总额可差 ±1e-8 级尾差(无减法兜底);
     * 末笔(fillQty ≥ 剩余 totalQty 口径由调用方保证)释放全额,残余尾差由 unfreeze 的
     * used 下限 clamp 吸收,不累积。
     *
     * <p>纯计算(无状态),抽到 domain 供 ExecutionService.processExecutionReport +
     * TradingTransactionHelper 共用,消除 ExecutionService static 跨类引用
     * (architect MAJOR #10;computeProportionalFrozen 移 Order domain)。
     *
     * @param frozenQuoteAmount 模拟盘 BUY 单冻结的 quote 金额(SELL 单 null,直接返 null)
     * @param fillQty 本次成交量
     * @param totalQty 订单总量(amount);null/<=0 返 frozenQuoteAmount(退化)
     * @return 按比例折算的冻结额(fillQty>=totalQty 返 frozenQuoteAmount 全量;否则 frozen*fillQty/totalQty 8位 HALF_UP)
     */
    public static BigDecimal computeProportionalFrozen(
            BigDecimal frozenQuoteAmount, BigDecimal fillQty, BigDecimal totalQty) {
        if (frozenQuoteAmount == null) {
            return null;
        }
        if (totalQty == null || totalQty.signum() <= 0) {
            return frozenQuoteAmount;
        }
        if (fillQty.compareTo(totalQty) >= 0) {
            return frozenQuoteAmount;
        }
        return frozenQuoteAmount.multiply(fillQty).divide(totalQty, 8, RoundingMode.HALF_UP);
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
     * reduceOnly 纯派生:CLOSE_* 自动 true(平仓自动 reduceOnly,前端不显式传);
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
