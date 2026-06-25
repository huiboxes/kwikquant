package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import io.github.ccxt.types.OHLCV;
import java.math.BigDecimal;
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

    private final io.github.ccxt.Exchange ccxtExchange;
    private final String symbol;
    private final Interval interval;
    private final Consumer<Kline> callback;
    private final Exchange exchange;
    private final MarketType marketType;
    private final long watchTimeoutSeconds;
    private volatile Thread thread;

    public CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType) {
        this(ccxtExchange, symbol, interval, callback, exchange, marketType, 60);
    }

    /** 测试用：注入短超时以快速触发 TimeoutException 分支。 */
    CcxtKlineWorker(
            io.github.ccxt.Exchange ccxtExchange,
            String symbol,
            Interval interval,
            Consumer<Kline> callback,
            Exchange exchange,
            MarketType marketType,
            long watchTimeoutSeconds) {
        this.ccxtExchange = ccxtExchange;
        this.symbol = symbol;
        this.interval = interval;
        this.callback = callback;
        this.exchange = exchange;
        this.marketType = marketType;
        this.watchTimeoutSeconds = watchTimeoutSeconds;
    }

    @Override
    public void start() {
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

    private void loop() {
        int backoffMs = 1000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var raw = ccxtExchange
                        .watchOHLCV(symbol, interval.ccxtValue())
                        .get(watchTimeoutSeconds, TimeUnit.SECONDS);
                Kline kline = convertLastCandle(raw);
                if (kline != null) {
                    callback.accept(kline);
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
                log.warn("watchOHLCV timeout on {} {}, retrying", symbol, interval);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            } catch (Exception e) {
                log.error("unexpected error watching kline {}/{}: {}", symbol, interval, e.getMessage(), e);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
        log.info("kline worker stopped: {}.{}.{} {}", exchange, marketType, symbol, interval);
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

    @SuppressWarnings("unchecked")
    private Kline convertLastCandle(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        var last = list.get(list.size() - 1);
        if (!(last instanceof OHLCV ohlcv)) {
            return null;
        }
        return new Kline(
                exchange,
                marketType,
                symbol,
                interval,
                ohlcv.timestamp != null ? Instant.ofEpochMilli(ohlcv.timestamp) : Instant.now(),
                toBd(ohlcv.open),
                toBd(ohlcv.high),
                toBd(ohlcv.low),
                toBd(ohlcv.close),
                toBd(ohlcv.volume));
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
