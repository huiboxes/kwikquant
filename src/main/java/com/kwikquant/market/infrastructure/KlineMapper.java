package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Kline;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
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
                high    = GREATEST(klines.high, EXCLUDED.high),
                low     = LEAST(klines.low, EXCLUDED.low),
                close   = EXCLUDED.close,
                volume  = EXCLUDED.volume
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
                    k.interval().name(),
                    k.openTime(),
                    k.open(),
                    k.high(),
                    k.low(),
                    k.close(),
                    k.volume());
        }
    }
}
