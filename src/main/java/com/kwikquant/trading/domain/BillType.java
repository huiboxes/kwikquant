package com.kwikquant.trading.domain;

/**
 * 账单类型(档位 B 实盘 PERP 强平/资金费率/ADL 同步,从 OKX bills type 码抽象)。
 *
 * <p>反腐层:{@code LiquidationService}/{@code FundingSettlementService} 按 {@link BillType} 分流,
 * 不感知 OKX type int 码(5 强平/8 资金费率/9 ADL)。OKX raw → BillType 映射在
 * {@code OkxOrderTranslator.parseBills}(infrastructure)。
 */
public enum BillType {
    LIQUIDATION, // 强平(OKX type=5)
    FUNDING, // 资金费率(OKX type=8)
    ADL, // 自动减仓(OKX type=9)
    OTHER; // 其他(consumer 忽略:1 Transfer/2 Trade/...)

    /** OKX bills type int 码 → BillType,未知码归 OTHER。 */
    public static BillType fromOkxType(int type) {
        return switch (type) {
            case 5 -> LIQUIDATION;
            case 8 -> FUNDING;
            case 9 -> ADL;
            default -> OTHER;
        };
    }
}
