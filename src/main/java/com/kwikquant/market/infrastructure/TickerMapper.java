package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Ticker;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TickerMapper {

    @Insert(
            """
            INSERT INTO tickers (exchange, market_type, symbol,
                                 last_price, bid, ask, high, low, open_price,
                                 base_volume, quote_volume, change, percentage,
                                 event_time, received_at)
            VALUES (#{exchange}, #{marketType}, #{symbol},
                    #{last}, #{bid}, #{ask}, #{high}, #{low}, #{open},
                    #{baseVolume}, #{quoteVolume}, #{change}, #{percentage},
                    #{eventTime}, #{receivedAt})
            ON CONFLICT (exchange, market_type, symbol)
            DO UPDATE SET
                last_price   = EXCLUDED.last_price,
                bid          = EXCLUDED.bid,
                ask          = EXCLUDED.ask,
                high         = EXCLUDED.high,
                low          = EXCLUDED.low,
                open_price   = EXCLUDED.open_price,
                base_volume  = EXCLUDED.base_volume,
                quote_volume = EXCLUDED.quote_volume,
                change       = EXCLUDED.change,
                percentage   = EXCLUDED.percentage,
                event_time   = EXCLUDED.event_time,
                received_at  = EXCLUDED.received_at
            """)
    void upsert(TickerRow row);

    @Select(
            """
            SELECT exchange, market_type, symbol,
                   last_price AS "last", bid, ask, high, low,
                   open_price AS "open",
                   base_volume AS "baseVolume",
                   quote_volume AS "quoteVolume",
                   change, percentage,
                   event_time AS "timestamp",
                   received_at AS "receivedAt"
            FROM tickers
            WHERE exchange = #{exchange} AND market_type = #{marketType}
              AND symbol = #{symbol}
            """)
    Ticker findLatest(String exchange, String marketType, String symbol);

    record TickerRow(
            String exchange,
            String marketType,
            String symbol,
            BigDecimal last,
            BigDecimal bid,
            BigDecimal ask,
            BigDecimal high,
            BigDecimal low,
            BigDecimal open,
            BigDecimal baseVolume,
            BigDecimal quoteVolume,
            BigDecimal change,
            BigDecimal percentage,
            Instant eventTime,
            Instant receivedAt) {

        public static TickerRow from(Ticker t) {
            return new TickerRow(
                    t.exchange().name(),
                    t.marketType().name(),
                    t.symbol(),
                    t.last(),
                    t.bid(),
                    t.ask(),
                    t.high(),
                    t.low(),
                    t.open(),
                    t.baseVolume(),
                    t.quoteVolume(),
                    t.change(),
                    t.percentage(),
                    t.timestamp(),
                    t.receivedAt());
        }
    }
}
