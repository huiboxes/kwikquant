package com.kwikquant.account.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 模拟盘余额记录(PaperExchange 内部持久化状态,非应用层账本)。
 *
 * <p>free=可用,used=冻结(挂单预占),total=free+used。CAS version 乐观锁,同 Position 模式。
 * 余额变更(冻结/解冻/扣减/入账)走 {@code PaperBalanceAdapter} 的 CAS 重试。
 */
public final class PaperBalance {

    private Long id;
    private long accountId;
    private String currency;
    private BigDecimal free;
    private BigDecimal used;
    private BigDecimal total;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public PaperBalance() {}

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

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getFree() {
        return free;
    }

    public void setFree(BigDecimal free) {
        this.free = free;
    }

    public BigDecimal getUsed() {
        return used;
    }

    public void setUsed(BigDecimal used) {
        this.used = used;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
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
