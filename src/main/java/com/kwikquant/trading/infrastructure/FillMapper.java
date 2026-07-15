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

@Mapper
public interface FillMapper {

    @Insert(
            """
            INSERT INTO fills (order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                               liquidity, external_fill_id, filled_at)
            VALUES (#{orderId}, #{accountId}, #{symbol}, #{side}, #{price}, #{qty}, #{fee},
                    #{feeCurrency}, #{liquidity}, #{externalFillId}, #{filledAt})
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

    /** 按账户汇总成交量和手续费（替代 Java 层 N+1 循环）。 */
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

    @Select(
            """
            SELECT COALESCE(
                SUM(CASE WHEN side = 'SELL' THEN price * qty - fee
                         ELSE -(price * qty + fee) END),
                0)
            FROM fills
            WHERE account_id = #{accountId} AND filled_at >= #{since}
            """)
    BigDecimal sumNetCashflow(@Param("accountId") long accountId, @Param("since") Instant since);

    /** 按日盈亏统计结果：总交易天数 + 盈利天数。 */
    record DailyWinLossResult(long totalDays, long winDays) {}

    /** 按日分组统计净现金流，返回总天数和盈利天数（胜率 = winDays / totalDays）。 */
    @Select(
            """
            SELECT
                COUNT(*) AS total_days,
                COUNT(CASE WHEN daily_cf > 0 THEN 1 END) AS win_days
            FROM (
                SELECT DATE_TRUNC('day', filled_at) AS day,
                       SUM(CASE WHEN side = 'SELL' THEN price * qty - fee
                                ELSE -(price * qty + fee) END) AS daily_cf
                FROM fills
                WHERE account_id = #{accountId} AND filled_at >= #{since}
                GROUP BY DATE_TRUNC('day', filled_at)
            ) sub
            """)
    DailyWinLossResult countDailyWinLoss(@Param("accountId") long accountId, @Param("since") Instant since);
}
