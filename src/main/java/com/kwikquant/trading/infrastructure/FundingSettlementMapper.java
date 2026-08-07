package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.FundingSettlement;
import java.math.BigDecimal;
import java.util.List;
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
 * <p>{@link #sumFundingAmountByAccountAndSymbol} 供 {@code PositionEnricher} 富化持仓视图时填
 * cumulativeFunding 字段(REST PositionDto 与 MCP PositionView 共用);{@link #listByAccountAndSymbol}
 * 供 MCP {@code get_funding_history} 工具查历史结算明细。
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

    /**
     * 查某账户资金费结算历史明细。symbol 可空查全部,按 settle_time 倒序,limit 上限 200(MCP 调用方截断)。
     */
    @Select(
            """
            <script>
            SELECT id, account_id, position_id, symbol, funding_rate, qty_at_settle, funding_amount,
                settle_time, bill_id, created_at
            FROM funding_settlements
            WHERE account_id = #{accountId}
            <if test="symbol != null and symbol != ''">
                AND symbol = #{symbol}
            </if>
            ORDER BY settle_time DESC
            LIMIT #{limit}
            </script>
            """)
    List<FundingSettlement> listByAccountAndSymbol(
            @Param("accountId") long accountId, @Param("symbol") String symbol, @Param("limit") int limit);
}
