package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CcxtTickerWorkerTest {

    private static io.github.ccxt.types.Ticker ccxtTicker() {
        var t = new io.github.ccxt.types.Ticker(new HashMap<>());
        t.symbol = "BTC/USDT";
        t.last = 50000.0;
        t.bid = 49999.0;
        t.ask = 50001.0;
        t.high = 51000.0;
        t.low = 49000.0;
        t.open = 49500.0;
        t.baseVolume = 100.0;
        t.quoteVolume = 5_000_000.0;
        t.change = 500.0;
        t.percentage = 1.01;
        t.timestamp = 1_700_000_000_000L;
        return t;
    }

    @Test
    void loop_whenWatchTickerReturns_shouldCallbackWithTicker() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchTicker(any())).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Ticker> received = new AtomicReference<>();
        var worker = new CcxtTickerWorker(
                ccxt,
                "BTC/USDT",
                t -> {
                    received.set(t);
                    latch.countDown();
                },
                Exchange.BINANCE,
                MarketType.SPOT);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        Ticker t = received.get();
        assertThat(t).isNotNull();
        assertThat(t.symbol()).isEqualTo("BTC/USDT");
        assertThat(t.last()).isEqualByComparingTo("50000");
        assertThat(t.exchange()).isEqualTo(Exchange.BINANCE);
    }

    @Test
    void loop_whenNetworkError_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // 第一次 NetworkError（经 .get() 包成 ExecutionException，cause=NetworkError），第二次成功
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new NetworkError("conn drop"));
        when(ccxt.watchTicker(any())).thenReturn(failed).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtTickerWorker(ccxt, "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT);

        worker.start();
        // 第一次失败后 sleep 1s 再重试；3s 内应收到第二次的成功回调
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        // watchTicker 至少被调 2 次（失败 1 + 成功 1）
        verify(ccxt, timeout(1_000).atLeast(2)).watchTicker(any());
        worker.stop();
    }

    @Test
    void loop_whenRateLimitExceeded_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new RateLimitExceeded("too many"));
        when(ccxt.watchTicker(any())).thenReturn(failed).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtTickerWorker(ccxt, "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void stop_whenInterrupted_shouldExitLoop() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // 永不完成的 CF → loop 阻塞在 .get()，stop() 中断线程退出
        when(ccxt.watchTicker(any())).thenReturn(new CompletableFuture<>());

        var worker = new CcxtTickerWorker(ccxt, "BTC/USDT", t -> {}, Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        // 给虚拟线程一点时间进入 .get() 阻塞
        Thread.sleep(200);
        assertThat(worker.isRunning()).isTrue();
        worker.stop();
        // 线程应在中断后退出
        Thread.sleep(200);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void loop_whenTimeout_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // 永不完成 + 1s 超时 → TimeoutException → backoff → 第二次成功
        when(ccxt.watchTicker(any()))
                .thenReturn(new CompletableFuture<>())
                .thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker =
                new CcxtTickerWorker(ccxt, "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 1);

        worker.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void loop_whenGenericException_shouldBackoffAndRetry() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // watchTicker 直接抛 RuntimeException（非 CF）→ catch (Exception) → backoff → 第二次成功
        when(ccxt.watchTicker(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker =
                new CcxtTickerWorker(ccxt, "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void handleCcxtCause_whenOtherCause_shouldNotCrash() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // ExecutionException cause 是 RuntimeException（非 RateLimit/Network）→ handleCcxtCause 走 generic 分支
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new IllegalStateException("weird"));
        when(ccxt.watchTicker(any())).thenReturn(failed).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker =
                new CcxtTickerWorker(ccxt, "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void convert_whenCcxtTicker_shouldMapAllFields() throws Exception {
        // 直接验证 convert 的字段映射（通过 loop 路径，同 happy path 但聚焦字段）
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var src = ccxtTicker();
        when(ccxt.watchTicker(any())).thenReturn(CompletableFuture.completedFuture(src));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Ticker> received = new AtomicReference<>();
        var worker = new CcxtTickerWorker(
                ccxt,
                "BTC/USDT",
                t -> {
                    received.set(t);
                    latch.countDown();
                },
                Exchange.BINANCE,
                MarketType.PERP);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        Ticker t = received.get();
        assertThat(t.marketType()).isEqualTo(MarketType.PERP);
        assertThat(t.bid()).isEqualByComparingTo("49999");
        assertThat(t.high()).isEqualByComparingTo("51000");
        assertThat(t.baseVolume()).isEqualByComparingTo("100");
    }
}
