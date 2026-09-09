package com.kwikquant.market.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * funding_rates 期次序列读写。冲突语义：settled_rate 一旦写入不被 predicted-only 行覆盖
 * （COALESCE 保旧值）；predicted_rate 期内滚动更新、结算后自然冻结（API 不再返该期预估）；
 * source 只随 settled 行更新（PROXY_BINANCE 标记来自跨所回填，实时采集不把它冲掉）。
 */
@Mapper
public interface FundingRateMapper {

    @Insert(
            """
            INSERT INTO funding_rates (exchange, symbol, funding_time, settled_rate, predicted_rate,
                                       interval_seconds, mark_price, source)
            VALUES (#{exchange}, #{symbol}, #{fundingTime}, #{settledRate}, #{predictedRate},
                    #{intervalSeconds}, #{markPrice}, #{source})
            ON CONFLICT (exchange, symbol, funding_time)
            DO UPDATE SET
                settled_rate     = COALESCE(EXCLUDED.settled_rate, funding_rates.settled_rate),
                predicted_rate   = COALESCE(EXCLUDED.predicted_rate, funding_rates.predicted_rate),
                interval_seconds = COALESCE(EXCLUDED.interval_seconds, funding_rates.interval_seconds),
                mark_price       = COALESCE(EXCLUDED.mark_price, funding_rates.mark_price),
                source           = CASE WHEN EXCLUDED.settled_rate IS NOT NULL
                                        THEN EXCLUDED.source ELSE funding_rates.source END,
                updated_at       = now()
            """)
    void upsert(FundingRateRow row);

    /** 回填/采集批量 upsert（500 行/批，与 klines 快照分批一致），冲突语义同 {@link #upsert}。 */
    @Insert({
        "<script>",
        "INSERT INTO funding_rates (exchange, symbol, funding_time, settled_rate, predicted_rate,",
        "                           interval_seconds, mark_price, source) VALUES",
        "<foreach collection='rows' item='r' separator=','>",
        "  (#{r.exchange}, #{r.symbol}, #{r.fundingTime}, #{r.settledRate}, #{r.predictedRate},",
        "   #{r.intervalSeconds}, #{r.markPrice}, #{r.source})",
        "</foreach>",
        "ON CONFLICT (exchange, symbol, funding_time)",
        "DO UPDATE SET",
        "  settled_rate     = COALESCE(EXCLUDED.settled_rate, funding_rates.settled_rate),",
        "  predicted_rate   = COALESCE(EXCLUDED.predicted_rate, funding_rates.predicted_rate),",
        "  interval_seconds = COALESCE(EXCLUDED.interval_seconds, funding_rates.interval_seconds),",
        "  mark_price       = COALESCE(EXCLUDED.mark_price, funding_rates.mark_price),",
        "  source           = CASE WHEN EXCLUDED.settled_rate IS NOT NULL",
        "                          THEN EXCLUDED.source ELSE funding_rates.source END,",
        "  updated_at       = now()",
        "</script>"
    })
    void batchUpsert(@Param("rows") List<FundingRateRow> rows);

    /** 期次键精确查询（LIVE 账单富化 / 单期结算用）；无行返 null。 */
    @Select(
            """
            SELECT exchange, symbol, funding_time, settled_rate, predicted_rate,
                   interval_seconds, mark_price, source
            FROM funding_rates
            WHERE exchange = #{exchange} AND symbol = #{symbol} AND funding_time = #{fundingTime}
            """)
    FundingRateRow findByKey(String exchange, String symbol, Instant fundingTime);

    /** 区间查询：funding_time ∈ [start, end)，ASC（回测资金费回放 / 结算 catch-up 消费用）。 */
    @Select(
            """
            SELECT exchange, symbol, funding_time, settled_rate, predicted_rate,
                   interval_seconds, mark_price, source
            FROM funding_rates
            WHERE exchange = #{exchange} AND symbol = #{symbol}
              AND funding_time >= #{start} AND funding_time < #{end}
            ORDER BY funding_time ASC
            """)
    List<FundingRateRow> findRange(String exchange, String symbol, Instant start, Instant end);

    /** 覆盖度概览（回填 skip 判定用）：总行数 / 最早期 / 最晚期 / 已结算行数。 */
    @Select(
            """
            SELECT count(*)                                         AS total,
                   min(funding_time)                                AS min_time,
                   max(funding_time)                                AS max_time,
                   count(*) FILTER (WHERE settled_rate IS NOT NULL) AS settled
            FROM funding_rates
            WHERE exchange = #{exchange} AND symbol = #{symbol}
            """)
    Coverage findCoverage(String exchange, String symbol);

    record FundingRateRow(
            String exchange,
            String symbol,
            Instant fundingTime,
            BigDecimal settledRate,
            BigDecimal predictedRate,
            Integer intervalSeconds,
            BigDecimal markPrice,
            String source) {}

    record Coverage(long total, Instant minTime, Instant maxTime, long settled) {}
}
