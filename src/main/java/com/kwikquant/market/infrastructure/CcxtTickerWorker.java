package com.kwikquant.market.infrastructure;

import static com.kwikquant.shared.types.NumberUtils.asBd;
import static com.kwikquant.shared.types.NumberUtils.asLong;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.time.Instant;
import java.util.Map;
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

    private final io.github.ccxt.Exchange ccxtExchange;
    private final String symbol;
    private final Consumer<Ticker> callback;
    private final Exchange exchange;
    private final MarketType marketType;
    private final long watchTimeoutSeconds;
    private volatile Thread thread;

    public CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType) {
        this(ccxtExchange, symbol, callback, exchange, marketType, 30);
    }

    /** 测试用：注入短超时以快速触发 TimeoutException 分支。 */
    CcxtTickerWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            Consumer<Ticker> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds) {
        this.ccxtExchange = ccxtExchange;
        this.symbol = symbol;
        this.callback = callback;
        this.exchange = exchange;
        this.marketType = marketType;
        this.watchTimeoutSeconds = watchTimeoutSeconds;
    }

    @Override
    public void start() {
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
        int backoffMs = 1000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var raw = ccxtExchange.watchTicker(symbol).get(watchTimeoutSeconds, TimeUnit.SECONDS);
                Ticker ticker = convert(raw);
                if (ticker != null) {
                    callback.accept(ticker);
                }
                backoffMs = 1000;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                handleCcxtCause(e.getCause(), backoffMs);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            } catch (TimeoutException e) {
                log.warn("watchTicker timeout on {}, retrying", symbol);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            } catch (Exception e) {
                log.error("unexpected error watching ticker {}: {}", symbol, e.getMessage(), e);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
        log.info("ticker worker stopped: {}.{}.{}", exchange, marketType, symbol);
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
     * 将 CCXT watchTicker 返回的原始 ticker dict（{@code Map<String,Object>}）转为 domain Ticker。
     *
     * <p>E2E 实测（见 context.md）：基类 {@code Exchange.watchTicker(Object...)} 返回的 CF 完成后是
     * 原始 {@code LinkedHashMap}（CCXT 标准化 ticker dict），<b>不是</b> typed {@code io.github.ccxt.types.Ticker}。
     * 故不能强转 Ticker（会 CCE），要按 Map key 读字段。key 名是 CCXT 标准化的（symbol/last/bid/ask/
     * high/low/open/baseVolume/quoteVolume/change/percentage/timestamp），对所有交易所一致。
     */
    @SuppressWarnings("unchecked")
    private Ticker convert(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            log.warn(
                    "watchTicker returned non-Map: {}",
                    raw == null ? "null" : raw.getClass().getName());
            return null;
        }
        Long ts = asLong(m.get("timestamp"));
        return new Ticker(
                exchange,
                marketType,
                asString(m.get("symbol"), this.symbol),
                asBd(m.get("last")),
                asBd(m.get("bid")),
                asBd(m.get("ask")),
                asBd(m.get("high")),
                asBd(m.get("low")),
                asBd(m.get("open")),
                asBd(m.get("baseVolume")),
                asBd(m.get("quoteVolume")),
                asBd(m.get("change")),
                asBd(m.get("percentage")),
                ts != null ? Instant.ofEpochMilli(ts) : Instant.now(),
                Instant.now());
    }

    private static String asString(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
