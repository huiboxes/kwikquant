package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Kline;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KlineMapper {

    @Insert(
            """
            INSERT INTO klines (exchange, market_type, symbol, interval, open_time,
                                open, high, low, close, volume)
            VALUES (#{exchange}, #{marketType}, #{symbol}, #{interval},
                    #{openTime}, #{open}, #{high}, #{low}, #{close}, #{volume})
            ON CONFLICT (exchange, market_type, symbol, interval, open_time)
            DO UPDATE SET
                high       = GREATEST(klines.high, EXCLUDED.high),
                low        = LEAST(klines.low, EXCLUDED.low),
                close      = EXCLUDED.close,
                volume     = EXCLUDED.volume,
                updated_at = now()
            """)
    void upsert(KlineRow row);

    @Select(
            """
            SELECT exchange, market_type, symbol, interval, open_time,
                   open, high, low, close, volume
            FROM klines
            WHERE exchange = #{exchange} AND market_type = #{marketType}
              AND symbol = #{symbol} AND interval = #{interval}
            ORDER BY open_time DESC
            LIMIT #{limit}
            """)
    List<Kline> findRecent(String exchange, String marketType, String symbol, String interval, int limit);

    /**
     * 往前滚加载历史:拉 open_time &lt; before 的最近 N 根(DESC LIMIT),前端 sort ASC + prepend。
     * 用于 K线图用户滚到左边时按需加载更早历史(生产级体验)。返 DESC(最新在前),消费方排序。
     */
    @Select(
            """
            SELECT exchange, market_type, symbol, interval, open_time,
                   open, high, low, close, volume
            FROM klines
            WHERE exchange = #{exchange} AND market_type = #{marketType}
              AND symbol = #{symbol} AND interval = #{interval}
              AND open_time < #{before}
            ORDER BY open_time DESC
            LIMIT #{limit}
            """)
    List<Kline> findBefore(
            String exchange, String marketType, String symbol, String interval, Instant before, int limit);

    /** 区间查询(回测数据快照用):open_time ∈ [start, end),ASC。 */
    @Select(
            """
            SELECT exchange, market_type, symbol, interval, open_time,
                   open, high, low, close, volume
            FROM klines
            WHERE exchange = #{exchange} AND market_type = #{marketType}
              AND symbol = #{symbol} AND interval = #{interval}
              AND open_time >= #{start} AND open_time < #{end}
            ORDER BY open_time ASC
            """)
    List<Kline> findRange(
            String exchange, String marketType, String symbol, String interval, Instant start, Instant end);

    /** 批量快照 upsert(回测拉取落库)。冲突合并语义与 {@link #upsert} 一致(high GREATEST/low LEAST)。 */
    @Insert({
        "<script>",
        "INSERT INTO klines (exchange, market_type, symbol, interval, open_time,",
        "                    open, high, low, close, volume) VALUES",
        "<foreach collection='rows' item='r' separator=','>",
        "  (#{r.exchange}, #{r.marketType}, #{r.symbol}, #{r.interval}, #{r.openTime},",
        "   #{r.open}, #{r.high}, #{r.low}, #{r.close}, #{r.volume})",
        "</foreach>",
        "ON CONFLICT (exchange, market_type, symbol, interval, open_time)",
        "DO UPDATE SET",
        "  high       = GREATEST(klines.high, EXCLUDED.high),",
        "  low        = LEAST(klines.low, EXCLUDED.low),",
        "  close      = EXCLUDED.close,",
        "  volume     = EXCLUDED.volume,",
        "  updated_at = now()",
        "</script>"
    })
    void batchUpsert(@Param("rows") List<KlineRow> rows);

    record KlineRow(
            String exchange,
            String marketType,
            String symbol,
            String interval,
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume) {

        public static KlineRow from(Kline k) {
            return new KlineRow(
                    k.exchange().name(),
                    k.marketType().name(),
                    k.symbol(),
                    k.interval().ccxtValue(),
                    k.openTime(),
                    k.open(),
                    k.high(),
                    k.low(),
                    k.close(),
                    k.volume());
        }
    }
}
