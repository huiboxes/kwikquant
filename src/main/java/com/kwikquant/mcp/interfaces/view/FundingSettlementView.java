package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.trading.domain.FundingSettlement;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP {@code get_funding_history} 工具返回的资金费结算明细投影。剥掉 domain 无关字段,
 * 对齐 {@code funding_settlements} 表列。
 *
 * <p><b>不暴露 billId</b>:billId 是内部幂等键(实盘=OKX 账单 ID,PAPER="PAPER-{posId}-{ts}"
 * 前缀,见 {@code FundingSettlementService.processFundingSettlement}),View 层返回会泄露 PAPER/LIVE
 * 枚举给 MCP Agent,与 event 层({@code FundingSettlementService} afterCommit 把 fBillId 设 null 防泄露)
 * 保持一致口径。
 */
public record FundingSettlementView(
        Long id,
        long accountId,
        Long positionId,
        String symbol,
        BigDecimal fundingRate,
        BigDecimal qtyAtSettle,
        BigDecimal fundingAmount,
        Instant settleTime,
        Instant createdAt) {

    public static FundingSettlementView from(FundingSettlement s) {
        return new FundingSettlementView(
                s.getId(),
                s.getAccountId(),
                s.getPositionId(),
                s.getSymbol(),
                s.getFundingRate(),
                s.getQtyAtSettle(),
                s.getFundingAmount(),
                s.getSettleTime(),
                s.getCreatedAt());
    }
}
