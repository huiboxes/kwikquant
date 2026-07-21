package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
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
    /** 合约杠杆(PERP);SPOT null。§13 拍板 1。 */
    private Integer leverage;
    /** 合约保证金模式 ISOLATED/CROSS(PERP);SPOT null。§13 拍板。 */
    private MarginMode marginMode;
    /** 合约持仓方向 LONG/SHORT(PERP 双向);SPOT null。side 字符串 long/short/flat 保留兼容(§10 M14)。 */
    private String positionSide;
    /** 强平价(逐仓简化公式 §3.2);SPOT null。§10 B4 casUpdate 减仓不变。 */
    private BigDecimal liquidationPrice;
    /** 维持保证金;SPOT null。§10 B4 开仓算不变。 */
    private BigDecimal maintMargin;
    /** per-position 累积 initialMargin(SPOT=0/PERP);§13 拍板 1,V32 frozen_amount 列。逐仓强平判此列 + 派生 unrealizedPnl(§12 B1-s)。 */
    private BigDecimal frozenAmount;
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

    public String getPositionSide() {
        return positionSide;
    }

    public void setPositionSide(String positionSide) {
        this.positionSide = positionSide;
    }

    public BigDecimal getLiquidationPrice() {
        return liquidationPrice;
    }

    public void setLiquidationPrice(BigDecimal liquidationPrice) {
        this.liquidationPrice = liquidationPrice;
    }

    public BigDecimal getMaintMargin() {
        return maintMargin;
    }

    public void setMaintMargin(BigDecimal maintMargin) {
        this.maintMargin = maintMargin;
    }

    public BigDecimal getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(BigDecimal frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    /** marketType 从 marginMode 派生(§13 M8-impl):null→SPOT,ISOLATED/CROSS→PERP。Position 不存 marketType DB 列。 */
    public MarketType getMarketType() {
        return marginMode == null ? MarketType.SPOT : MarketType.PERP;
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
