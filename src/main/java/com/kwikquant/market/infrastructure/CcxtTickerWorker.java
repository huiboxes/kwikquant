package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Virtual Thread 循环调 CCXT Pro watchTicker，收到 update 回调 MarketDataService.onTicker。
 *
 * <p>设计偏差（见 context.md）：CCXT Java 4.5.59 的 watchTicker 在 Exchange 基类返回
 * {@code CompletableFuture<Object>}，故用 {@code .get(timeout)} 取结果并强转为
 * {@link io.github.ccxt.types.Ticker}。异常经 ExecutionException 包裹，需 unwrap 区分
 * RateLimitExceeded/NetworkError 的 retryable 语义。
 */
public class CcxtTickerWorker implements Stoppable {

    private static final Logger log = LoggerFactory.getLogger(CcxtTickerWorker.class);

    private static final long DEFAULT_WATCH_TIMEOUT_SECONDS = 30;
    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int MAX_BACKOFF_MS = 30_000;

    private final io.github.ccxt.Exchange ccxtExchange;
    private final String symbol;
    private final String ccxtSymbol;
    private final Consumer<Ticker> callback;
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

    public CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType) {
        this(ccxtExchange, symbol, ccxtSymbol, callback, exchange, marketType, DEFAULT_WATCH_TIMEOUT_SECONDS);
    }

    /** 测试用：注入短超时以快速触发 TimeoutException 分支。 */
    CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds) {
        this(
                ccxtExchange,
                symbol,
                ccxtSymbol,
                callback,
                exchange,
                marketType,
                watchTimeoutSeconds,
                MarketFallbackProperties.DEFAULT_WS_FALLBACK_AFTER_FAILURES,
                MarketFallbackProperties.DEFAULT_REST_POLL_INTERVAL.toMillis(),
                MarketFallbackProperties.DEFAULT_WS_RETRY_INTERVAL.toMillis());
    }

    /** 默认 watch 超时 + 可配置降级参数(由 {@link MarketDataService} 从 {@link MarketFallbackProperties} 传入)。 */
    public CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType,
            int wsFallbackAfterFailures,
            long restPollIntervalMs,
            long wsRetryIntervalMs) {
        this(
                ccxtExchange,
                symbol,
                ccxtSymbol,
                callback,
                exchange,
                marketType,
                DEFAULT_WATCH_TIMEOUT_SECONDS,
                wsFallbackAfterFailures,
                restPollIntervalMs,
                wsRetryIntervalMs);
    }

    /** 完整构造：超时 + 降级参数(由 {@link MarketDataService} 从 {@link MarketFallbackProperties} 传入)。 */
    CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            String ccxtSymbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds,
            int wsFallbackAfterFailures,
            long restPollIntervalMs,
            long wsRetryIntervalMs) {
        this.ccxtExchange = ccxtExchange;
        this.symbol = symbol;
        this.ccxtSymbol = ccxtSymbol;
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
        String threadName = "ticker-" + exchange + "-" + marketType + "-" + symbol.replace("/", "");
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
                // REST 轮询兜底:WS 持续失败后仍保证行情可用;期间按周期探测 WS 是否恢复。
                // 探测计时以"探测完成后"为基准,且探测失败不再 continue——否则探测阻塞会吞掉
                // 整个轮询窗口,REST 兜底被饿死(恰是降级要救的场景)。
                if (System.currentTimeMillis() - lastWsProbeMillis >= wsRetryIntervalMs) {
                    boolean recovered = probeWsRecovery();
                    lastWsProbeMillis = System.currentTimeMillis();
                    if (recovered) {
                        polling = false;
                        wsFailures = 0;
                        backoffMs = INITIAL_BACKOFF_MS;
                        log.info("watchTicker recovered for {}.{}, back to WS mode", exchange, symbol);
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
                raw = ccxtExchange.watchTicker(ccxtSymbol).get(watchTimeoutSeconds, TimeUnit.SECONDS);
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
                log.warn("watchTicker timeout on {}, retrying", symbol);
                wsFailures = onWsFailure(wsFailures, backoffMs);
                if (wsFailures >= wsFallbackAfterFailures) {
                    polling = true;
                    lastWsProbeMillis = System.currentTimeMillis();
                } else {
                    backoffMs = Math.min(backoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
                }
                continue;
            } catch (Exception e) {
                log.error("unexpected error watching ticker {}: {}", symbol, e.getMessage(), e);
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
        log.info("ticker worker stopped: {}.{}.{}", exchange, marketType, symbol);
    }

    /** 派发回调:转换 + 下发。失败仅记日志(属下游问题),不计入 WS 降级状态机。 */
    private void dispatchCallback(Object raw) {
        try {
            Ticker ticker = convert(raw);
            if (ticker != null) {
                callback.accept(ticker);
            }
        } catch (Exception e) {
            log.warn("ticker callback failed for {}.{} (downstream, not WS): {}", exchange, symbol, String.valueOf(e));
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
            raw = ccxtExchange.fetchTicker(ccxtSymbol).join();
        } catch (Exception e) {
            log.warn("REST fetchTicker failed on {}.{}, will retry: {}", exchange, symbol, String.valueOf(e));
            return;
        }
        dispatchCallback(raw);
    }

    /** 降级期间探测 WS 是否恢复:仅以"能否从 WS 取到数"判定恢复;回调派发失败不否定恢复。 */
    private boolean probeWsRecovery() {
        Object raw;
        try {
            raw = ccxtExchange.watchTicker(ccxtSymbol).get(watchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("WS probe still failing on {}.{}: {}", exchange, symbol, String.valueOf(e));
            return false;
        }
        dispatchCallback(raw);
        return true;
    }

    private void handleCcxtCause(Throwable cause, int backoffMs) {
        // RateLimitExceeded extends NetworkError，先判子类
        if (cause instanceof RateLimitExceeded rle) {
            log.warn("rate limited on {}, backing off {}ms", symbol, backoffMs);
        } else if (cause instanceof NetworkError ne) {
            log.warn("network error on {}: {}, retrying in {}ms", symbol, ne.getMessage(), backoffMs);
        } else {
            log.error("ccxt error watching ticker {}: {}", symbol, String.valueOf(cause));
        }
    }

    /**
     * 将 CCXT watchTicker 返回的原始 ticker dict 转为 domain Ticker。
     *
     * <p>逻辑抽到 {@link CcxtTickerAdapter}(与 {@code MarketDataService.fetchTicker} REST fallback 共用)。
     * E2E 实测:基类 {@code Exchange.watchTicker} CF 完成值是原始 {@code LinkedHashMap}
     * (CCXT 标准化 ticker dict,不是 typed {@code io.github.ccxt.types.Ticker}),按 Map key 读字段,
     * key 名对所有交易所一致(symbol/last/bid/ask/high/low/open/baseVolume/quoteVolume/change/
     * percentage/timestamp)。raw 非 Map 时 adapter 抛 {@link IllegalArgumentException},这里 catch
     * 转 log+null(保留 worker 宽容行为:单条异常不影响循环续命)。
     */
    private Ticker convert(Object raw) {
        try {
            return CcxtTickerAdapter.toKwikquant(raw, exchange, marketType, symbol);
        } catch (IllegalArgumentException e) {
            log.warn("watchTicker returned non-Map: {}", e.getMessage());
            return null;
        }
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
