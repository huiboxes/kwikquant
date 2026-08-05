package com.kwikquant.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 资金费率结算落账(档位 B,对应 V43 funding_settlements 表)。
 *
 * <p>OKX PERP 8h 资金费率结算,OKX /api/v5/account/bills type=8 账单拉到后落账。
 * 不复用 fills 表(fills 语义是"成交",资金费率不是成交)。
 *
 * <p>字段语义见 V43 迁移 + spec §4。
 */
public class FundingSettlement {

    private Long id;
    private long accountId;
    private Long positionId;
    private String symbol;
    private BigDecimal fundingRate;
    private BigDecimal qtyAtSettle;
    private BigDecimal fundingAmount;
    private Instant settleTime;
    private String billId;
    private Instant createdAt;

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

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getFundingRate() {
        return fundingRate;
    }

    public void setFundingRate(BigDecimal fundingRate) {
        this.fundingRate = fundingRate;
    }

    public BigDecimal getQtyAtSettle() {
        return qtyAtSettle;
    }

    public void setQtyAtSettle(BigDecimal qtyAtSettle) {
        this.qtyAtSettle = qtyAtSettle;
    }

    public BigDecimal getFundingAmount() {
        return fundingAmount;
    }

    public void setFundingAmount(BigDecimal fundingAmount) {
        this.fundingAmount = fundingAmount;
    }

    public Instant getSettleTime() {
        return settleTime;
    }

    public void setSettleTime(Instant settleTime) {
        this.settleTime = settleTime;
    }

    public String getBillId() {
        return billId;
    }

    public void setBillId(String billId) {
        this.billId = billId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
