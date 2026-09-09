package com.kwikquant.market.application;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.FundingRateHistoryPoint;
import com.kwikquant.market.infrastructure.FundingDataProperties;
import com.kwikquant.market.infrastructure.FundingRateMapper;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金费率期次序列采集器：把配置内 PERP symbol 的已结算/预估费率写入 funding_rates 表。
 *
 * <p>每轮两条路：① {@code fetchFundingRate} 实时快照 → predicted 行（期次键 = fundingTime）；OKX 的
 * info 另带上期已结算值（settFundingRate）→ settled 行同期次键落库；② {@code fetchFundingRateHistory}
 * 回看窗口内补 settled 行——覆盖 Binance 等没有 info.settFundingRate 的交易所，也补宕机漏掉的已结算期。
 *
 * <p>OKX 历史窗口仅约 94 天、窗口外数据永久流失：生产环境此采集必须常开，停机超过 sweep 回看窗口
 * 造成的缺口不可恢复。单 symbol 失败聚合记一条 warn 不中断（无外网环境不应刷爆日志、更不应拖垮应用）。
 */
@Component
public class FundingRateCollector {

    private static final Logger log = LoggerFactory.getLogger(FundingRateCollector.class);

    static final String SOURCE_EXCHANGE = "EXCHANGE";
    /** batchUpsert 分块(8 绑定参数/行,500 行远低于 PG 65535 参数上限,对齐 klines 快照分批)。 */
    private static final int UPSERT_BATCH = 500;

    private final MarketDataService marketDataService;
    private final FundingRateMapper fundingRateMapper;
    private final FundingDataProperties properties;

    public FundingRateCollector(
            MarketDataService marketDataService,
            FundingRateMapper fundingRateMapper,
            FundingDataProperties properties) {
        this.marketDataService = marketDataService;
        this.fundingRateMapper = fundingRateMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${kwikquant.funding.collector.interval-ms:300000}")
    void collect() {
        if (!properties.collector().enabled()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (Exchange exchange : properties.exchanges()) {
            for (String symbol : properties.symbols()) {
                try {
                    collectOne(exchange, symbol);
                } catch (RuntimeException e) {
                    failures.add(exchange + "." + symbol + ": " + e.getMessage());
                }
            }
        }
        if (!failures.isEmpty()) {
            log.warn(
                    "[funding-collector] {} pair(s) failed this round: {}",
                    failures.size(),
                    String.join("; ", failures));
        }
    }

    void collectOne(Exchange exchange, String symbol) {
        FundingRate fr = marketDataService.fetchFundingRate(exchange, MarketType.PERP, symbol);
        // 快照行与 history 路径同款脏数据守卫:rate null 不落空行;markPrice ≤0(交易所缺省/脏值)
        // 置 null——下游 paper 结算对 markPrice≤0 有 fallback,但落库脏值会污染诊断与回填覆盖判定
        if (fr.fundingTime() != null && fr.fundingRate() != null) {
            fundingRateMapper.upsert(new FundingRateMapper.FundingRateRow(
                    exchange.name(),
                    symbol,
                    fr.fundingTime(),
                    null,
                    fr.fundingRate(),
                    fr.intervalSeconds(),
                    fr.markPrice() != null && fr.markPrice().signum() > 0 ? fr.markPrice() : null,
                    SOURCE_EXCHANGE));
        }
        // OKX info.settFundingRate:所属期次 = fundingTime − interval,拿到即落,不等 history sweep
        Instant settledTime = fr.settledFundingTime();
        if (fr.settledFundingRate() != null && settledTime != null) {
            fundingRateMapper.upsert(new FundingRateMapper.FundingRateRow(
                    exchange.name(),
                    symbol,
                    settledTime,
                    fr.settledFundingRate(),
                    null,
                    fr.intervalSeconds(),
                    null,
                    SOURCE_EXCHANGE));
        }
        Instant until = Instant.now();
        Instant since = until.minus(properties.collector().settledSweepLookback());
        persistSettled(
                exchange,
                symbol,
                marketDataService.fetchFundingRateHistory(exchange, MarketType.PERP, symbol, since, until));
    }

    /** 已结算期次点落库(分块 batchUpsert)。回填服务复用同一入口,保证 upsert 语义单点。 */
    void persistSettled(Exchange exchange, String symbol, List<FundingRateHistoryPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<FundingRateMapper.FundingRateRow> rows = points.stream()
                .map(p -> new FundingRateMapper.FundingRateRow(
                        exchange.name(), symbol, p.fundingTime(), p.rate(), null, null, null, SOURCE_EXCHANGE))
                .toList();
        for (int i = 0; i < rows.size(); i += UPSERT_BATCH) {
            fundingRateMapper.batchUpsert(rows.subList(i, Math.min(rows.size(), i + UPSERT_BATCH)));
        }
    }
}
