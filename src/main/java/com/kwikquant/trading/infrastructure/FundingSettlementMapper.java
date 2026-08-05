package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.FundingSettlement;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 资金费率结算落账 Mapper(V43 funding_settlements 表)。
 *
 * <p>insert 走 {@code UNIQUE(account_id, bill_id)} 幂等——同 bill 重复 insert 抛
 * {@link org.springframework.dao.DuplicateKeyException},{@code FundingSettlementService}
 * catch 当幂等成功(已处理直接 return)。
 *
 * <p>{@link #sumFundingAmountByAccountAndSymbol} 供 PositionController 拉 PositionDto 时
 * 填 cumulativeFunding 字段(前端持仓表"累计资金费"列展示)。
 */
@Mapper
public interface FundingSettlementMapper {

    @Insert(
            """
            INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,
                qty_at_settle, funding_amount, settle_time, bill_id, created_at)
            VALUES (#{accountId}, #{positionId}, #{symbol}, #{fundingRate},
                #{qtyAtSettle}, #{fundingAmount}, #{settleTime}, #{billId}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FundingSettlement settlement);

    /** 汇总某账户某 symbol 的累计资金费(SUM(funding_amount))。无记录返 0。 */
    @Select(
            """
            SELECT COALESCE(SUM(funding_amount), 0)
            FROM funding_settlements
            WHERE account_id = #{accountId} AND symbol = #{symbol}
            """)
    BigDecimal sumFundingAmountByAccountAndSymbol(@Param("accountId") long accountId, @Param("symbol") String symbol);
}
