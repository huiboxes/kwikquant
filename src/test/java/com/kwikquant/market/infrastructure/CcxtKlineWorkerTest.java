package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CcxtKlineWorkerTest {

    /** 模拟 CCXT watchOHLCV 返回的原始 candle（位置数组 [ts, open, high, low, close, volume]），与 E2E 实测一致。 */
    private static List<Object> candle(double open, double high, double low, double close, double vol) {
        return List.of(1_700_000_000_000L, open, high, low, close, vol);
    }

    @Test
    void loop_whenWatchOhlcvReturns_shouldCallbackWithKline() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(candle(50000, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Kline> received = new AtomicReference<>();
        var worker = new CcxtKlineWorker(
                ccxt,
                "BTC/USDT",
                Interval._1m,
                k -> {
                    received.set(k);
                    latch.countDown();
                },
                Exchange.BINANCE,
                MarketType.SPOT);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        Kline k = received.get();
        assertThat(k).isNotNull();
        assertThat(k.symbol()).isEqualTo("BTC/USDT");
        assertThat(k.interval()).isEqualTo(Interval._1m);
        assertThat(k.open()).isEqualByComparingTo("50000");
        assertThat(k.close()).isEqualByComparingTo("50050");
    }

    @Test
    void convertLastCandle_whenCcxtOhlcvList_shouldMapToKline() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // 多根 candle，应取最后一根（最新）
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        candle(50000, 50010, 49990, 50005, 1),
                        candle(50005, 50020, 49980, 50015, 2),
                        candle(50015, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Kline> received = new AtomicReference<>();
        var worker = new CcxtKlineWorker(
                ccxt,
                "BTC/USDT",
                Interval._1m,
                k -> {
                    received.set(k);
                    latch.countDown();
                },
                Exchange.BINANCE,
                MarketType.PERP);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        Kline k = received.get();
        assertThat(k.close()).isEqualByComparingTo("50050");
        assertThat(k.high()).isEqualByComparingTo("50100");
        assertThat(k.low()).isEqualByComparingTo("49900");
        assertThat(k.volume()).isEqualByComparingTo("12.5");
        assertThat(k.marketType()).isEqualTo(MarketType.PERP);
    }

    @Test
    void stop_whenInterrupted_shouldExitLoop() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any())).thenReturn(new CompletableFuture<>());

        var worker = new CcxtKlineWorker(ccxt, "BTC/USDT", Interval._1m, k -> {}, Exchange.BINANCE, MarketType.SPOT);

        worker.start();
        Thread.sleep(200);
        assertThat(worker.isRunning()).isTrue();
        worker.stop();
        Thread.sleep(200);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void loop_whenTimeout_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(new CompletableFuture<>())
                .thenReturn(CompletableFuture.completedFuture(List.of(candle(50000, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 1);

        worker.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void loop_whenGenericException_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(CompletableFuture.completedFuture(List.of(candle(50000, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void convertLastCandle_whenEmptyOrNull_shouldNotCallback() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        // 用 latch 计数：callback 若被调会 countDown；测试末尾断言仍为 1（未回调）
        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        // 给 worker 时间完成 watchOHLCV + convertLastCandle（空列表 → 返 null → 不回调）
        Thread.sleep(500);
        worker.stop();
        assertThat(latch.getCount()).isEqualTo(1); // callback 未被调用
    }

    @Test
    void convertLastCandle_whenNonOhlcvElement_shouldNotCallback() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.<Object>of("not-an-ohlcv")));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        Thread.sleep(500);
        worker.stop();
        assertThat(latch.getCount()).isEqualTo(1); // callback 未被调用
    }

    @Test
    void loop_whenNetworkError_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new NetworkError("conn drop"));
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(failed)
                .thenReturn(CompletableFuture.completedFuture(List.of(candle(50000, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void loop_whenRateLimitExceeded_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new RateLimitExceeded("too many"));
        when(ccxt.watchOHLCV(any(), any()))
                .thenReturn(failed)
                .thenReturn(CompletableFuture.completedFuture(List.of(candle(50000, 50100, 49900, 50050, 12.5))));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtKlineWorker(
                ccxt, "BTC/USDT", Interval._1m, k -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }
}
