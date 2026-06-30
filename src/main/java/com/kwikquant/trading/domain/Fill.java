package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成交记录。Order 的关联值对象，独立持久化（fills 表）。
 *
 * <p>{@code externalFillId}: 实盘是 CCXT 返回的 trade id；Backtest/Paper 是 Executor 生成的 UUID。 与
 * {@code accountId} 构成 §1.4 幂等键。
 *
 * <p>{@code liquidity}: maker（限价成交）或 taker（市价 / 限价穿越）。
 *
 * <p>使用 class 而非 record，以便 MyBatis 通过 setter 注入 auto-generated id。
 */
public class Fill {

    private Long id;
    private long orderId;
    private long accountId;
    private String symbol;
    private OrderSide side;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal fee;
    private String feeCurrency;
    private String liquidity;
    private String externalFillId;
    private Instant filledAt;

    public Fill() {}

    public static Fill create(
            long orderId,
            long accountId,
            String symbol,
            OrderSide side,
            BigDecimal price,
            BigDecimal qty,
            BigDecimal fee,
            String feeCurrency,
            String liquidity,
            String externalFillId,
            Instant filledAt) {
        Fill f = new Fill();
        f.orderId = orderId;
        f.accountId = accountId;
        f.symbol = symbol;
        f.side = side;
        f.price = price;
        f.qty = qty;
        f.fee = fee;
        f.feeCurrency = feeCurrency;
        f.liquidity = liquidity;
        f.externalFillId = externalFillId;
        f.filledAt = filledAt;
        return f;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public String getFeeCurrency() {
        return feeCurrency;
    }

    public void setFeeCurrency(String feeCurrency) {
        this.feeCurrency = feeCurrency;
    }

    public String getLiquidity() {
        return liquidity;
    }

    public void setLiquidity(String liquidity) {
        this.liquidity = liquidity;
    }

    public String getExternalFillId() {
        return externalFillId;
    }

    public void setExternalFillId(String externalFillId) {
        this.externalFillId = externalFillId;
    }

    public Instant getFilledAt() {
        return filledAt;
    }

    public void setFilledAt(Instant filledAt) {
        this.filledAt = filledAt;
    }
}
