package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FillMapper {

    @Insert(
            """
            INSERT INTO fills (order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                               liquidity, external_fill_id, filled_at, realized_pnl_delta)
            VALUES (#{orderId}, #{accountId}, #{symbol}, #{side}, #{price}, #{qty}, #{fee},
                    #{feeCurrency}, #{liquidity}, #{externalFillId}, #{filledAt}, #{realizedPnlDelta})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Fill fill);

    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM fills
                          WHERE account_id = #{accountId}
                            AND external_fill_id = #{externalFillId})
            """)
    boolean existsByExternalFillId(@Param("accountId") long accountId, @Param("externalFillId") String externalFillId);

    @Select(
            """
            SELECT id, order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                   liquidity, external_fill_id, filled_at
            FROM fills
            WHERE order_id = #{orderId}
            ORDER BY filled_at ASC
            """)
    @Results({
        @Result(column = "order_id", property = "orderId"),
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "fee_currency", property = "feeCurrency"),
        @Result(column = "external_fill_id", property = "externalFillId"),
        @Result(column = "filled_at", property = "filledAt")
    })
    List<Fill> findByOrderId(@Param("orderId") long orderId);

    /** 批量查询多个订单的 fills，消除 N+1。 */
    @SelectProvider(type = FillSqlProvider.class, method = "findByOrderIds")
    @Results({
        @Result(column = "order_id", property = "orderId"),
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "fee_currency", property = "feeCurrency"),
        @Result(column = "external_fill_id", property = "externalFillId"),
        @Result(column = "filled_at", property = "filledAt")
    })
    List<Fill> findByOrderIds(@Param("orderIds") List<Long> orderIds);

    /** 按账户汇总成交量和有符号费用成本（普通费用为正,返佣为负）。 */
    @Select(
            """
            SELECT COALESCE(SUM(price * qty), 0) AS total_volume,
                   COALESCE(SUM(fee), 0) AS total_fees
            FROM fills
            WHERE account_id = #{accountId} AND filled_at >= #{since}
            """)
    com.kwikquant.trading.application.VolumeAndFees sumVolumeAndFees(
            @Param("accountId") long accountId, @Param("since") Instant since);

    class FillSqlProvider {
        public static String findByOrderIds(@Param("orderIds") List<Long> orderIds) {
            if (orderIds == null || orderIds.isEmpty()) {
                return "SELECT id, order_id, account_id, symbol, side, price, qty, fee, fee_currency, "
                        + "liquidity, external_fill_id, filled_at FROM fills WHERE 1=0";
            }
            StringBuilder sb =
                    new StringBuilder("SELECT id, order_id, account_id, symbol, side, price, qty, fee, fee_currency, "
                            + "liquidity, external_fill_id, filled_at FROM fills WHERE order_id IN (");
            for (int i = 0; i < orderIds.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(orderIds.get(i));
            }
            sb.append(") ORDER BY filled_at ASC");
            return sb.toString();
        }
    }

    /**
     * 已实现净损益:汇总 fills.realized_pnl_delta(方向性 PnL - 有符号费用成本)。
     * 供 {@code DAILY_LOSS_LIMIT} 风控和报告共用,替代旧净现金流口径
     * (净现金流把开仓 BUY 支出当亏损误拦,把 PERP OPEN_SHORT side=SELL 当收入虚高漏拦)。
     */
    @Select(
            """
            SELECT COALESCE(SUM(realized_pnl_delta), 0)
            FROM fills
            WHERE account_id = #{accountId} AND filled_at >= #{since}
            """)
    BigDecimal sumRealizedPnlDelta(@Param("accountId") long accountId, @Param("since") Instant since);

    /**
     * 回填单笔 fill 的 realized_pnl_delta。{@code ExecutionService} 在 fill insert 后调
     * {@code positionService.applyFill} 拿到平仓 PnL(SPOT/PERP),再回填——applyFill 必须在
     * insert 后(幂等:fill 已存在也 applyFill=double apply;并发 race 两 thread 都 applyFill
     * 但只一 insert 成功,故 applyFill 不能挪到 insert 前)。
     */
    @Update(
            """
            UPDATE fills SET realized_pnl_delta = #{delta}, updated_at = now()
            WHERE id = #{id}
            """)
    int updateRealizedPnlDelta(@Param("id") long id, @Param("delta") BigDecimal delta);

    /** 按日盈亏统计结果：总交易天数 + 盈利天数。 */
    record DailyWinLossResult(long totalDays, long winDays) {}

    /** 按日分组统计净已实现损益，返回总天数和盈利天数（胜率 = winDays / totalDays）。 */
    @Select(
            """
            SELECT
                COUNT(*) AS total_days,
                COUNT(CASE WHEN daily_pnl > 0 THEN 1 END) AS win_days
            FROM (
                SELECT DATE_TRUNC('day', filled_at) AS day,
                       SUM(realized_pnl_delta) AS daily_pnl
                FROM fills
                WHERE account_id = #{accountId} AND filled_at >= #{since}
                GROUP BY DATE_TRUNC('day', filled_at)
            ) sub
            """)
    DailyWinLossResult countDailyWinLoss(@Param("accountId") long accountId, @Param("since") Instant since);

    /**
     * 查某账户强平历史(强平 Fill 的 external_fill_id 以 "liq-" 前缀标记,由 LiquidationService 创建)。
     * symbol 可空查全部,按 filled_at 倒序,limit 上限 200(MCP 调用方截断)。
     */
    @Select(
            """
            <script>
            SELECT id, order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                   liquidity, external_fill_id, filled_at, realized_pnl_delta
            FROM fills
            WHERE account_id = #{accountId}
              AND external_fill_id LIKE 'liq-%'
            <if test="symbol != null and symbol != ''">
                AND symbol = #{symbol}
            </if>
            ORDER BY filled_at DESC
            LIMIT #{limit}
            </script>
            """)
    @Results({
        @Result(column = "order_id", property = "orderId"),
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "fee_currency", property = "feeCurrency"),
        @Result(column = "external_fill_id", property = "externalFillId"),
        @Result(column = "filled_at", property = "filledAt"),
        @Result(column = "realized_pnl_delta", property = "realizedPnlDelta")
    })
    List<Fill> listLiquidationsByAccount(
            @Param("accountId") long accountId, @Param("symbol") String symbol, @Param("limit") int limit);

    /**
     * runner 断线补拉播种:绑定账户当前**安全尾部** fill id(无行返 0)。worker 进程启动时调用,
     * 游标从"现在"起算——重启不回放历史事件(重启窗口缺口归 ctx.position()/REST 对账契约,
     * docs/strategy-api.md §8)。
     *
     * <p>{@code created_at} 安全边界(滞后 2s,DB 单时钟)与 {@link #findCommittedSince} 同口径:
     * 只数已提交可见行,播种游标不会越过在途事务。
     */
    @Select(
            """
            SELECT COALESCE(MAX(id), 0)
            FROM fills
            WHERE account_id = #{accountId}
              AND created_at < now() - interval '2 seconds'
            """)
    long maxCommittedFillId(@Param("accountId") long accountId);

    /**
     * runner 断线增量补拉:绑定账户 id &gt; afterId 的成交明细行(id ASC,前 limit 行),join orders
     * 还原 position_effect/market_type(两列在 orders 上,fills 表没有;runner 市场类型过滤与
     * on_fill payload 需要)。排除强平行(external_fill_id 前缀 liq-/bill-,NULL 安全):强平成交走
     * LiquidationEvent 通道不推 FillEvent(通道互斥,docs/ws-contract.md §5),补拉派发不得破坏。
     *
     * <p>{@code created_at} 安全边界(滞后 2s,DB 单时钟)只读已提交可见行——BIGSERIAL 分配序 ≠
     * 提交序(并发事务下 id 与 created_at 可交叉),无边界则游标可能越过尚未提交的行造成永久漏读;
     * 前提=fill 写入事务秒级提交(ExecutionService/LiquidationService 均为短事务)。worker 侧另有
     * 重叠重拉(afterId-100)+ fillId 去重兜底(kwikquant_worker/event_loop.py 补拉循环)。
     */
    @Select(
            """
            SELECT f.id, f.order_id, f.account_id, f.symbol, f.side, f.price, f.qty, f.fee,
                   f.fee_currency, f.liquidity, f.filled_at,
                   o.position_effect, o.market_type
            FROM fills f
            JOIN orders o ON o.id = f.order_id
            WHERE f.account_id = #{accountId}
              AND f.id > #{afterId}
              AND f.created_at < now() - interval '2 seconds'
              AND (f.external_fill_id IS NULL
                   OR (f.external_fill_id NOT LIKE 'liq-%' AND f.external_fill_id NOT LIKE 'bill-%'))
            ORDER BY f.id
            LIMIT #{limit}
            """)
    List<com.kwikquant.trading.application.FillCatchupRow> findCommittedSince(
            @Param("accountId") long accountId, @Param("afterId") long afterId, @Param("limit") int limit);
}
