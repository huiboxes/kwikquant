package com.kwikquant.market.application;

import com.kwikquant.market.domain.FundingRateHistoryPoint;
import com.kwikquant.market.infrastructure.FundingDataProperties;
import com.kwikquant.market.infrastructure.FundingRateMapper;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 历史资金费率序列一次性回填（ApplicationReady 时按配置执行，失败只 warn 不阻断启动）。
 *
 * <p>数据源事实（2026-09 实测）：OKX funding-rate-history 仅回溯约 94 天，窗口外永久缺失——尽早
 * 回、能抢多少抢多少；Binance fapi 近全历史（2019 起可取），作为 OKX 长窗口回测的代理源（跨所
 * 基差由消费侧显式声明，本服务只如实落 source=EXCHANGE 的本所数据）。
 *
 * <p>分页推进取页内 max fundingTime+1（防交易所返 DESC，与 K 线区间拉取同款防御），不推进即 break
 * 防死循环；页间固定间隔避公共端点限频。upsert 幂等，同区间重复执行只刷 updated_at。覆盖度已够
 * （最早行盖住目标起点、最新行近 24h 内）即跳过，重启不打交易所。
 */
@Service
public class FundingRateBackfillService {

    private static final Logger log = LoggerFactory.getLogger(FundingRateBackfillService.class);

    private final MarketDataService marketDataService;
    private final FundingRateMapper fundingRateMapper;
    private final FundingRateCollector collector;
    private final FundingDataProperties properties;

    public FundingRateBackfillService(
            MarketDataService marketDataService,
            FundingRateMapper fundingRateMapper,
            FundingRateCollector collector,
            FundingDataProperties properties) {
        this.marketDataService = marketDataService;
        this.fundingRateMapper = fundingRateMapper;
        this.collector = collector;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (!properties.backfill().enabled()) {
            return;
        }
        Instant now = Instant.now();
        for (Exchange exchange : properties.exchanges()) {
            for (String symbol : properties.symbols()) {
                try {
                    int upserted = backfill(exchange, symbol, now);
                    if (upserted > 0) {
                        log.info("[funding-backfill] {}.{}: upserted {} settled periods", exchange, symbol, upserted);
                    }
                } catch (RuntimeException e) {
                    log.warn("[funding-backfill] {}.{} failed: {}", exchange, symbol, e.getMessage());
                }
            }
        }
    }

    int backfill(Exchange exchange, String symbol, Instant now) {
        // OKX 窗口有限走 lookback;其余所(Binance fapi)近全历史走固定起点
        Instant since = exchange == Exchange.OKX
                ? now.minus(properties.backfill().okxLookback())
                : properties.backfill().longHistorySince();
        if (coverageComplete(exchange, symbol, since, now)) {
            log.debug("[funding-backfill] {}.{} coverage complete, skip", exchange, symbol);
            return 0;
        }
        long sinceMs = since.toEpochMilli();
        long untilMs = now.toEpochMilli();
        int total = 0;
        while (sinceMs < untilMs) {
            List<FundingRateHistoryPoint> page = marketDataService.fetchFundingRateHistory(
                    exchange, MarketType.PERP, symbol, Instant.ofEpochMilli(sinceMs), Instant.ofEpochMilli(untilMs));
            if (page.isEmpty()) {
                break;
            }
            collector.persistSettled(exchange, symbol, page);
            total += page.size();
            long maxTs = page.stream()
                    .mapToLong(p -> p.fundingTime().toEpochMilli())
                    .max()
                    .orElse(0L);
            if (maxTs + 1 <= sinceMs) {
                break; // 交易所返回的数据不推进 since(异常/超出窗口),防死循环
            }
            sinceMs = maxTs + 1;
            if (!pause()) {
                break; // 被中断:提前结束,幂等 upsert 下次启动续传
            }
        }
        return total;
    }

    /**
     * 覆盖度判定(三段):头部——仅 OKX 对照配置窗口(首部署须拉满 94d 窗口头);BINANCE 头部
     * 是标的上市时刻(2019-09 起各标的不一,不可预知),不对照 longHistorySince——旧判据
     * {@code minTime ≤ since+1d} 对上市晚于 since+1d 的标的恒 false,导致每次重启全量重拉近
     * 7 年历史(deploy.md"覆盖已够即跳过"承诺失真)。尾部——最新行在 24h 内(采集器常开时
     * 天然满足)。中段——停机超过 collector sweep 窗(48h)留下的洞首尾判定看不见,按相邻期次
     * 差分网格归属检测({@link FundingSeries#hasGridGaps}),有洞即重拉(幂等 upsert 修复)。
     */
    boolean coverageComplete(Exchange exchange, String symbol, Instant since, Instant now) {
        FundingRateMapper.Coverage c = fundingRateMapper.findCoverage(exchange.name(), symbol);
        if (c == null || c.total() == 0) {
            return false;
        }
        if (c.maxTime() == null || !c.maxTime().isAfter(now.minus(Duration.ofHours(24)))) {
            return false;
        }
        if (exchange == Exchange.OKX && (c.minTime() == null || c.minTime().isAfter(since.plus(Duration.ofDays(1))))) {
            return false;
        }
        // 先做同期次双行去重再判洞:OKX 快照派生键(精确网格)与 history 原生键(结算漂移)可并存,
        // 双行产生的秒级差分不属任何网格簇,不去重会让覆盖判定永假 → 每次启动全量重拉且 upsert 无法自愈
        List<Instant> settledTimes = FundingSeries.dedupeSamePeriod(
                        fundingRateMapper.findRange(exchange.name(), symbol, since, now).stream()
                                .filter(r -> r.settledRate() != null)
                                .toList())
                .stream()
                .map(FundingRateMapper.FundingRateRow::fundingTime)
                .toList();
        return !FundingSeries.hasGridGaps(settledTimes);
    }

    private boolean pause() {
        long ms = properties.backfill().pagePause().toMillis();
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
