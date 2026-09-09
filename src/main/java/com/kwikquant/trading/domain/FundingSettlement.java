package com.kwikquant.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 资金费率结算落账(对应 V43 funding_settlements 表,V59 期次化扩展)。
 *
 * <p>OKX PERP 资金费率按交易所期次网格结算(8h/4h/1h):LIVE 从 /api/v5/account/bills
 * type=8 账单落账,PAPER 由调度器按期次算金额落账。不复用 fills 表(fills 语义是"成交",
 * 资金费率不是成交)。
 *
 * <p>期次字段(V59):{@code fundingTime} 是期次键(与 account_id/position_id 组成期次幂等键,
 * PAPER 行 settleTime := fundingTime);{@code rateKind} 标注费率值来源(SETTLED/PREDICTED);
 * {@code intervalSeconds}/{@code markPrice} best-effort 富化(未知为 null)。字段语义见迁移。
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
    private Instant fundingTime;
    private FundingRateKind rateKind;
    private Integer intervalSeconds;
    private BigDecimal markPrice;
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

    public Instant getFundingTime() {
        return fundingTime;
    }

    public void setFundingTime(Instant fundingTime) {
        this.fundingTime = fundingTime;
    }

    public FundingRateKind getRateKind() {
        return rateKind;
    }

    public void setRateKind(FundingRateKind rateKind) {
        this.rateKind = rateKind;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(Integer intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public BigDecimal getMarkPrice() {
        return markPrice;
    }

    public void setMarkPrice(BigDecimal markPrice) {
        this.markPrice = markPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
