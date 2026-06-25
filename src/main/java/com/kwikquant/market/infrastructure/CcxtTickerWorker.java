package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.math.BigDecimal;
import java.time.Instant;
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
                callback.accept(convert(raw));
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

    private Ticker convert(Object raw) {
        var ct = (io.github.ccxt.types.Ticker) raw;
        // ct.symbol 理论上可能为 null（某些 edge case CCXT 不回填），用 worker 已知的 symbol 兜底
        String symbol = ct.symbol != null ? ct.symbol : this.symbol;
        return new Ticker(
                exchange,
                marketType,
                symbol,
                toBd(ct.last),
                toBd(ct.bid),
                toBd(ct.ask),
                toBd(ct.high),
                toBd(ct.low),
                toBd(ct.open),
                toBd(ct.baseVolume),
                toBd(ct.quoteVolume),
                toBd(ct.change),
                toBd(ct.percentage),
                ct.timestamp != null ? Instant.ofEpochMilli(ct.timestamp) : Instant.now(),
                Instant.now());
    }

    private static BigDecimal toBd(Double v) {
        return v != null ? BigDecimal.valueOf(v) : null;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
