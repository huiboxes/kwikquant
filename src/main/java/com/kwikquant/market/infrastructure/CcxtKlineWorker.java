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

    /** 测试可观测：worker 线程是否仍在运行。 */
    boolean isRunning() {
        return thread != null && thread.isAlive();
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
        return new Kline(
                exchange,
                marketType,
                symbol,
                interval,
                ts != null ? Instant.ofEpochMilli(ts) : Instant.now(),
                asBd(candle.get(1)), // open
                asBd(candle.get(2)), // high
                asBd(candle.get(3)), // low
                asBd(candle.get(4)), // close
                asBd(candle.get(5))); // volume
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
