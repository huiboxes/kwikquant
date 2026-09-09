package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.FundingSettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 资金费率结算落账 Mapper(V43 funding_settlements 表,V59 期次化)。
 *
 * <p>insert 走双幂等键:{@code UNIQUE(account_id, bill_id)}(LIVE 账单)与
 * {@code UNIQUE(account_id, position_id, funding_time)}(期次键,PAPER 主键;LIVE 行
 * position_id 可空时 NULLS DISTINCT 不撞)——重复 insert 抛
 * {@link org.springframework.dao.DuplicateKeyException},{@code FundingSettlementService}
 * catch 当幂等成功(已处理直接 return)。
 *
 * <p>{@link #sumFundingAmountByAccountAndSymbol} 供 {@code PositionEnricher} 富化持仓视图时填
 * cumulativeFunding 字段(REST PositionDto 与 MCP PositionView 共用);{@link #listByAccountAndSymbol}
 * 供 MCP {@code get_funding_history} 工具查历史结算明细;{@link #findLastFundingTime} 供
 * PAPER 调度器读持仓的最近已结算期次(catch-up watermark)。
 */
@Mapper
public interface FundingSettlementMapper {

    @Insert(
            """
            INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,
                qty_at_settle, funding_amount, settle_time, bill_id,
                funding_time, rate_kind, interval_seconds, mark_price, created_at)
            VALUES (#{accountId}, #{positionId}, #{symbol}, #{fundingRate},
                #{qtyAtSettle}, #{fundingAmount}, #{settleTime}, #{billId},
                #{fundingTime}, #{rateKind}, #{intervalSeconds}, #{markPrice}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FundingSettlement settlement);

    /**
     * 某持仓最近已结算期次的 watermark(MAX(funding_time)),无记录返 null。
     * PAPER catch-up 结算下界之一(与 positions.opened_at 取 max;双 null 走 fallback)。
     * 走 uq_funding_settlements_period 索引 (account_id, position_id, funding_time)。
     */
    @Select(
            """
            SELECT MAX(funding_time)
            FROM funding_settlements
            WHERE account_id = #{accountId} AND position_id = #{positionId}
            """)
    Instant findLastFundingTime(@Param("accountId") long accountId, @Param("positionId") long positionId);

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
                settle_time, bill_id, funding_time, rate_kind, interval_seconds, mark_price, created_at
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
