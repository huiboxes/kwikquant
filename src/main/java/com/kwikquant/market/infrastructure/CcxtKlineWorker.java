package com.kwikquant.market.infrastructure;

import static com.kwikquant.shared.types.NumberUtils.asBd;
import static com.kwikquant.shared.types.NumberUtils.asLong;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Virtual Thread 循环调 CCXT Pro watchOHLCV，收到最新 candle 回调 MarketDataService.onKline。
 *
 * <p>设计偏差（见 context.md）：CCXT Java 4.5.59 的 watchOHLCV 在 Exchange 基类返回
 * {@code CompletableFuture<Object>}，{@code .get(timeout)} 后强转为 {@code List<OHLCV>}，
 * 取最后一根（最新）candle。异常处理同 CcxtTickerWorker。
 */
public class CcxtKlineWorker implements Stoppable {

    private static final Logger log = LoggerFactory.getLogger(CcxtKlineWorker.class);

    private static final long DEFAULT_WATCH_TIMEOUT_SECONDS = 60;
    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int MAX_BACKOFF_MS = 30_000;

    private final io.github.ccxt.Exchange ccxtExchange;
    private final String symbol;
    private final String ccxtSymbol;
    private final Interval interval;
    private final Consumer<Kline> callback;
    private final Exchange exchange;
    private final MarketType marketType;
    private final long watchTimeoutSeconds;
    /** 连续 WS 失败达此次数后降级为 REST 轮询(见 {@link MarketFallbackProperties})。 */
    private final int wsFallbackAfterFailures;
    /** 降级后 REST 轮询周期(毫秒)。 */
    private final long restPollIntervalMs;
    /** 降级期间每隔此毫秒数探测一次 WS 是否恢复。 */
    private final long wsRetryIntervalMs;

    private volatile Thread thread;

    public CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType) {
        this(ccxtExchange, symbol, ccxtSymbol, interval, callback, exchange, marketType, DEFAULT_WATCH_TIMEOUT_SECONDS);
    }

    /** 测试用：注入短超时以快速触发 TimeoutException 分支。 */
    CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds) {
        this(
                ccxtExchange,
                symbol,
                ccxtSymbol,
                interval,
                callback,
                exchange,
                marketType,
                watchTimeoutSeconds,
                MarketFallbackProperties.DEFAULT_WS_FALLBACK_AFTER_FAILURES,
                MarketFallbackProperties.DEFAULT_REST_POLL_INTERVAL.toMillis(),
                MarketFallbackProperties.DEFAULT_WS_RETRY_INTERVAL.toMillis());
    }

    /** 默认 watch 超时 + 可配置降级参数(由 {@link MarketDataService} 从 {@link MarketFallbackProperties} 传入)。 */
    public CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType,
            int wsFallbackAfterFailures,
            long restPollIntervalMs,
            long wsRetryIntervalMs) {
        this(
                ccxtExchange,
                symbol,
                ccxtSymbol,
                interval,
                callback,
                exchange,
                marketType,
                DEFAULT_WATCH_TIMEOUT_SECONDS,
                wsFallbackAfterFailures,
                restPollIntervalMs,
                wsRetryIntervalMs);
    }

    /** 完整构造：超时 + 降级参数(由 {@link MarketDataService} 从 {@link MarketFallbackProperties} 传入)。 */
    CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds,
            int wsFallbackAfterFailures,
            long restPollIntervalMs,
            long wsRetryIntervalMs) {
        this.ccxtExchange = ccxtExchange;
        this.symbol = symbol;
        this.ccxtSymbol = ccxtSymbol;
        this.interval = interval;
        this.callback = callback;
        this.exchange = exchange;
        this.marketType = marketType;
        this.watchTimeoutSeconds = watchTimeoutSeconds;
        this.wsFallbackAfterFailures = wsFallbackAfterFailures;
        this.restPollIntervalMs = restPollIntervalMs;
        this.wsRetryIntervalMs = wsRetryIntervalMs;
    }

    @Override
    public void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        String threadName =
                "kline-" + exchange + "-" + marketType + "-" + symbol.replace("/", "") + "-" + interval.ccxtValue();
        thread = Thread.ofVirtual().name(threadName).start(this::loop);
    }

    @Override
    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 测试可观测：worker 线程是否仍在运行。 */
    boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    private void loop() {
        int backoffMs = INITIAL_BACKOFF_MS;
        boolean marketsLoaded = false;
        int wsFailures = 0;
        boolean polling = false;
        long lastWsProbeMillis = 0;
        while (!Thread.currentThread().isInterrupted()) {
            // loadMarkets 走 REST,WS/轮询两种模式都需要;先于模式分派执行
            if (!marketsLoaded) {
                try {
                    ccxtExchange.loadMarkets().get(watchTimeoutSeconds, TimeUnit.SECONDS);
                    marketsLoaded = true;
                    log.info("loaded markets for {} {}", exchange, symbol);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // loadMarkets 走 REST,其失败是"市场未加载"而非 WS 故障:不计入 wsFailures、
                    // 不触发降级,只退避重试(否则一段瞬时 REST 抖动会让 worker 带病进入轮询模式)。
                    // 保留退避睡眠:REST 挂时 loadMarkets 毫秒级失败,不睡眠会退化成热循环。
                    log.warn("loadMarkets failed for {}.{}, will retry: {}", exchange, symbol, String.valueOf(e));
                    sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
                    continue;
                }
            }
            if (polling) {
                // REST 轮询兜底:WS 持续失败后仍保证 K 线推送可用;期间按周期探测 WS 是否恢复。
                // 探测计时以"探测完成后"为基准,且探测失败不再 continue——否则探测阻塞会吞掉
                // 整个轮询窗口,REST 兜底被饿死(恰是降级要救的场景)。
                if (System.currentTimeMillis() - lastWsProbeMillis >= wsRetryIntervalMs) {
                    boolean recovered = probeWsRecovery();
                    lastWsProbeMillis = System.currentTimeMillis();
                    if (recovered) {
                        polling = false;
                        wsFailures = 0;
                        backoffMs = INITIAL_BACKOFF_MS;
                        log.info("watchOHLCV recovered for {}.{} {}, back to WS mode", exchange, symbol, interval);
                        continue;
                    }
                }
                pollOnceRest();
                sleep(restPollIntervalMs);
                continue;
            }
            // WS 模式:先取数并按取数结果维护降级状态机;回调派发独立,下游异常不计为 WS 故障
            Object raw;
            try {
                raw = ccxtExchange
                        .watchOHLCV(ccxtSymbol, interval.ccxtValue())
                        .get(watchTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                handleCcxtCause(e.getCause(), backoffMs);
                wsFailures = onWsFailure(wsFailures, backoffMs);
                if (wsFailures >= wsFallbackAfterFailures) {
                    polling = true;
                    lastWsProbeMillis = System.currentTimeMillis();
                } else {
                    backoffMs = Math.min(backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
                }
                continue;
            } catch (TimeoutException e) {
                log.warn("watchOHLCV timeout on {} {}, retrying", symbol, interval);
                wsFailures = onWsFailure(wsFailures, backoffMs);
                if (wsFailures >= wsFallbackAfterFailures) {
                    polling = true;
                    lastWsProbeMillis = System.currentTimeMillis();
                } else {
                    backoffMs = Math.min(backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
                }
                continue;
            } catch (Exception e) {
                log.error("unexpected error watching kline {}/{}: {}", symbol, interval, e.getMessage(), e);
                wsFailures = onWsFailure(wsFailures, backoffMs);
                if (wsFailures >= wsFallbackAfterFailures) {
                    polling = true;
                    lastWsProbeMillis = System.currentTimeMillis();
                } else {
                    backoffMs = Math.min(backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
                }
                continue;
            }
            // 取数成功:重置失败计数;回调异常(如 DB 抖动)不归因为 WS 故障,避免误降级
            wsFailures = 0;
            backoffMs = INITIAL_BACKOFF_MS;
            dispatchCallback(raw);
        }
        log.info("kline worker stopped: {}.{}.{} {}", exchange, marketType, symbol, interval);
    }

    /** 派发回调:转换 + 下发。失败仅记日志(属下游问题),不计入 WS 降级状态机。 */
    private void dispatchCallback(Object raw) {
        try {
            Kline kline = convertLastCandle(raw);
            if (kline != null) {
                callback.accept(kline);
            }
        } catch (Exception e) {
            log.warn(
                    "kline callback failed for {}.{} {} (downstream, not WS): {}",
                    exchange,
                    symbol,
                    interval,
                    String.valueOf(e));
        }
    }

    /** WS 失败一次:计数 +1;未达降级阈值则按当前退避睡眠。返回更新后的失败计数。 */
    private int onWsFailure(int wsFailures, int backoffMs) {
        int next = wsFailures + 1;
        if (next < wsFallbackAfterFailures) {
            sleep(backoffMs);
        }
        return next;
    }

    /** REST 轮询一次:取数与派发分离——REST 取数失败记"取数失败",回调异常走 {@link #dispatchCallback}
     *  记"下游失败",二者归因不混淆(与 WS 路径一致)。 */
    private void pollOnceRest() {
        Object raw;
        try {
            raw = ccxtExchange
                    .fetchOHLCV(ccxtSymbol, interval.ccxtValue(), null, 1)
                    .join();
        } catch (Exception e) {
            log.warn(
                    "REST fetchOHLCV failed on {}.{} {}, will retry: {}",
                    exchange,
                    symbol,
                    interval,
                    String.valueOf(e));
            return;
        }
        dispatchCallback(raw);
    }

    /** 降级期间探测 WS 是否恢复:仅以"能否从 WS 取到数"判定恢复;回调派发失败不否定恢复。 */
    private boolean probeWsRecovery() {
        Object raw;
        try {
            raw = ccxtExchange.watchOHLCV(ccxtSymbol, interval.ccxtValue()).get(watchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("WS probe still failing on {}.{} {}: {}", exchange, symbol, interval, String.valueOf(e));
            return false;
        }
        dispatchCallback(raw);
        return true;
    }

    private void handleCcxtCause(Throwable cause, int backoffMs) {
        if (cause instanceof RateLimitExceeded) {
            log.warn("rate limited on kline {}/{}, backing off {}ms", symbol, interval, backoffMs);
        } else if (cause instanceof NetworkError ne) {
            log.warn(
                    "network error on kline {}/{}: {}, retrying in {}ms", symbol, interval, ne.getMessage(), backoffMs);
        } else {
            log.error("ccxt error watching kline {}/{}: {}", symbol, interval, String.valueOf(cause));
        }
    }

    /**
     * 将 CCXT watchOHLCV 返回的原始 candle 列表转为 domain Kline（取最后一根=最新）。
     *
     * <p>E2E 实测（见 context.md）：基类 {@code Exchange.watchOHLCV(Object...)} 返回
     * {@code ArrayList<ArrayList>}——每根 candle 是<b>位置数组</b> {@code [timestamp, open, high, low, close, volume]}，
     * 不是 typed {@code OHLCV}、也不是 Map。故按位置读字段（0=ts,1=open,2=high,3=low,4=close,5=volume）。
     */
    @SuppressWarnings("unchecked")
    private Kline convertLastCandle(Object raw) {
        if (!(raw instanceof List<?> candles) || candles.isEmpty()) {
            return null;
        }
        var last = candles.get(candles.size() - 1);
        if (!(last instanceof List<?> candle) || candle.size() < 6) {
            log.warn("watchOHLCV candle not a 6-element list: {}", last);
            return null;
        }
        Long ts = asLong(candle.get(0));
        if (ts == null) {
            log.warn("watchOHLCV candle missing timestamp: {}", candle);
            return null;
        }
        return new Kline(
                exchange,
                marketType,
                symbol,
                interval,
                Instant.ofEpochMilli(ts),
                asBd(candle.get(1)), // open
                asBd(candle.get(2)), // high
                asBd(candle.get(3)), // low
                asBd(candle.get(4)), // close
                asBd(candle.get(5))); // volume
    }

    /** 睡眠。收 long 而非 int:轮询周期来自配置,避免 {@code (int)} 强转在超大值时溢出为负、
     *  {@code Thread.sleep} 抛 {@code IllegalArgumentException} 令 worker 线程静默死亡。 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
