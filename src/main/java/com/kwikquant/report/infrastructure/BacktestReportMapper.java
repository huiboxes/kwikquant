package com.kwikquant.report.infrastructure;

import com.kwikquant.report.domain.BacktestReport;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BacktestReportMapper {

    @Insert(
            """
            INSERT INTO backtest_reports (user_id, name, params, symbol, timeframe,
                                          period_start, period_end, equity_curve, source)
            VALUES (#{userId}, #{name}, #{params}, #{symbol}, #{timeframe},
                    #{periodStart}, #{periodEnd},
                    CAST(#{equityCurve} AS jsonb),
                    #{source})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BacktestReport report);

    @Update(
            """
            UPDATE backtest_reports
            SET total_return = #{totalReturn},
                sharpe_ratio = #{sharpeRatio},
                max_drawdown = #{maxDrawdown},
                win_rate = #{winRate},
                profit_factor = #{profitFactor},
                total_trades = #{totalTrades},
                avg_trade_duration_seconds = #{avgTradeDurationSeconds},
                updated_at = now()
            WHERE id = #{id}
            """)
    void updateMetrics(BacktestReport report);

    @Select("SELECT * FROM backtest_reports WHERE id = #{id}")
    BacktestReport findById(@Param("id") long id);

    @Select({
        "<script>",
        "SELECT * FROM backtest_reports",
        "WHERE user_id = #{userId}",
        "<if test='symbol != null'>AND symbol = #{symbol}</if>",
        "ORDER BY created_at DESC",
        "LIMIT #{limit} OFFSET #{offset}",
        "</script>"
    })
    List<BacktestReport> findByUserId(
            @Param("userId") long userId,
            @Param("symbol") String symbol,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select({
        "<script>",
        "SELECT COUNT(*) FROM backtest_reports",
        "WHERE user_id = #{userId}",
        "<if test='symbol != null'>AND symbol = #{symbol}</if>",
        "</script>"
    })
    long countByUserId(@Param("userId") long userId, @Param("symbol") String symbol);

    @Select({
        "<script>",
        "SELECT * FROM backtest_reports",
        "WHERE user_id = #{userId}",
        "  AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<BacktestReport> findByIds(@Param("ids") List<Long> ids, @Param("userId") long userId);
}
