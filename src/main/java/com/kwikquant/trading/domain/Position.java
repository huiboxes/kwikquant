package com.kwikquant.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持仓聚合根。{@code (accountId, symbol)} 唯一。
 *
 * <p>{@code side} 取值: {@code long} / {@code short} / {@code flat}（字符串而非 enum，便于 DB 直接存）。
 *
 * <p>合约场景: side=long qty=0 等同 flat；现货场景: side 始终 long 或 flat。
 */
public class Position {

    public static final String SIDE_LONG = "long";
    public static final String SIDE_SHORT = "short";
    public static final String SIDE_FLAT = "flat";

    private Long id;
    private long accountId;
    private String symbol;
    private String side;
    private BigDecimal qty;
    private BigDecimal avgEntryPrice;
    private BigDecimal realizedPnl;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public Position() {}

    /** 工厂方法：新建一个 flat 持仓（首次创建时用）。 */
    public static Position flat(long accountId, String symbol) {
        Position p = new Position();
        p.accountId = accountId;
        p.symbol = symbol;
        p.side = SIDE_FLAT;
        p.qty = BigDecimal.ZERO;
        p.avgEntryPrice = null;
        p.realizedPnl = BigDecimal.ZERO;
        p.version = 0;
        return p;
    }

    public boolean isFlat() {
        return SIDE_FLAT.equals(side) || qty == null || qty.signum() == 0;
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

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public BigDecimal getAvgEntryPrice() {
        return avgEntryPrice;
    }

    public void setAvgEntryPrice(BigDecimal avgEntryPrice) {
        this.avgEntryPrice = avgEntryPrice;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
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
