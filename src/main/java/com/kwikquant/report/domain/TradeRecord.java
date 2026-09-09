package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 报告成交。fee 与 trading Fill 一致：普通费用为正成本，返佣为负成本。 */
public class TradeRecord {
    private long id;
    private long reportId;
    private Instant time;
    /** 成交所属标的(组合报告逐笔标记;单标的报告为 null,标的由所属报告的 symbol 隐含)。 */
    private String symbol;

    /**
     * PERP 四向意图(OPEN_LONG|OPEN_SHORT|CLOSE_LONG|CLOSE_SHORT;SPOT 行为 null)。
     * PERP 配对按净持仓 signed FIFO 走该字段(docs/perp-backtest-spec.md §8.2),
     * {@code side} 是派生量(buy≠开仓)不能用于配对。
     */
    private String positionEffect;

    /** 强平成交行标记(PERP bar 极值近似;SPOT 行恒 false)。 */
    private boolean liquidation;

    private String side;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal realizedPnl;
    private BigDecimal equity;
    private Instant createdAt;

    public TradeRecord() {}

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getReportId() {
        return reportId;
    }

    public void setReportId(long reportId) {
        this.reportId = reportId;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getPositionEffect() {
        return positionEffect;
    }

    public void setPositionEffect(String positionEffect) {
        this.positionEffect = positionEffect;
    }

    public boolean isLiquidation() {
        return liquidation;
    }

    public void setLiquidation(boolean liquidation) {
        this.liquidation = liquidation;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getEquity() {
        return equity;
    }

    public void setEquity(BigDecimal equity) {
        this.equity = equity;
    }
}
