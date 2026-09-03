package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.errors.NetworkError;
import io.github.ccxt.errors.RateLimitExceeded;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CcxtTickerWorkerTest {

    /** 模拟 CCXT watchTicker 返回的原始 ticker dict（LinkedHashMap），与 E2E 实测一致。 */
    private static Map<String, Object> ccxtTicker() {
        var m = new HashMap<String, Object>();
        m.put("symbol", "BTC/USDT");
        m.put("last", 50000.0);
        m.put("bid", 49999.0);
        m.put("ask", 50001.0);
        m.put("high", 51000.0);
        m.put("low", 49000.0);
        m.put("open", 49500.0);
        m.put("baseVolume", 100.0);
        m.put("quoteVolume", 5_000_000.0);
        m.put("change", 500.0);
        m.put("percentage", 1.01);
        m.put("timestamp", 1_700_000_000_000L);
        return m;
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
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT);

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
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT);

        worker.start();
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        worker.stop();
    }

    @Test
    void stop_whenInterrupted_shouldExitLoop() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        // 永不完成的 CF → loop 阻塞在 .get()，stop() 中断线程退出
        when(ccxt.watchTicker(any())).thenReturn(new CompletableFuture<>());

        var worker = new CcxtTickerWorker(ccxt, "BTC/USDT", "BTC/USDT", t -> {}, Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        // 给虚拟线程一点时间进入 .get() 阻塞
        Thread.sleep(200);
        assertThat(worker.isRunning()).isTrue();
        worker.stop();
        // 线程应在中断后退出
        Thread.sleep(200);
        assertThat(worker.isRunning()).isFalse();
    }

    /**
     * M4 回归测试：{@code start()} 必须满足 {@link Stoppable} 接口"重复调用无副作用"的幂等契约——
     * 已在运行的 worker 上重复调用 start() 不应替换掉 thread 引用（否则旧线程会泄漏，无法再被 stop()）。
     */
    @Test
    void start_calledTwiceWhileRunning_isIdempotent() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchTicker(any())).thenReturn(new CompletableFuture<>());

        var worker = new CcxtTickerWorker(ccxt, "BTC/USDT", "BTC/USDT", t -> {}, Exchange.BINANCE, MarketType.SPOT, 30);

        worker.start();
        Thread.sleep(200);
        assertThat(worker.isRunning()).isTrue();

        worker.start(); // 重复调用：不应启动第二个线程/丢弃第一个线程的引用
        Thread.sleep(200);
        assertThat(worker.isRunning()).isTrue();

        worker.stop();
        Thread.sleep(200);
        // stop() 必须能中断到最初启动的那个线程（而不是一个被第二次 start() 覆盖丢失的引用）
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
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 1);

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
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

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
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 30);

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
                "BTC/USDT:USDT",
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

    /**
     * 核心回归:PERP worker 必须用 {@code ccxtSymbol}({@code BTC/USDT:USDT})调 {@code watchTicker},
     * <b>不是</b> canonical {@code BTC/USDT}——后者在 swap 实例的 markets 表里不存在,WS 订阅永不命中,
     * 是 PERP topic 永远没数据的根因。同时断言 domain {@code Ticker.symbol} 仍是 canonical(翻译只在 CCXT 边界)。
     */
    @Test
    void loop_whenPerp_shouldWatchWithCcxtSymbolNotCanonical() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchTicker(any())).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Ticker> received = new AtomicReference<>();
        var worker = new CcxtTickerWorker(
                ccxt,
                "BTC/USDT",
                "BTC/USDT:USDT",
                t -> {
                    received.set(t);
                    latch.countDown();
                },
                Exchange.OKX,
                MarketType.PERP);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        // watchTicker 必须收到翻译后的 perp 符号;绝不能是 canonical spot 形式。
        // loop 在 stop 前可能已调多次,故用 atLeast(1)。
        verify(ccxt, timeout(1_000).atLeast(1)).watchTicker("BTC/USDT:USDT");
        verify(ccxt, never()).watchTicker("BTC/USDT");
        // domain Ticker.symbol 仍是 canonical,不把 :USDT 后缀泄漏到 DB/WS
        assertThat(received.get().symbol()).isEqualTo("BTC/USDT");
        assertThat(received.get().marketType()).isEqualTo(MarketType.PERP);
    }

    /**
     * Phase 2 兜底:连续 WS 失败达阈值后降级为 REST 轮询,仍经同一回调下发行情。
     * watchTicker 恒失败(模拟只通 REST 的受限网络),fetchTicker 正常 → 降级后回调收到 ticker。
     */
    @Test
    void loop_whenWsKeepsFailing_degradesToRestPolling() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new NetworkError("ws blocked"));
        when(ccxt.watchTicker(any())).thenReturn(failed);
        when(ccxt.fetchTicker(any())).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Ticker> received = new AtomicReference<>();
        // 阈值 2 次、轮询 20ms、恢复探测拉长(不触发);快速进入降级
        var worker = new CcxtTickerWorker(
                ccxt,
                "BTC/USDT",
                "BTC/USDT",
                t -> {
                    received.set(t);
                    latch.countDown();
                },
                Exchange.BINANCE,
                MarketType.SPOT,
                2,
                20,
                60_000);

        worker.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        // 确实走了 REST 兜底,且回调拿到的是 canonical ticker
        verify(ccxt, timeout(1_000).atLeast(1)).fetchTicker("BTC/USDT");
        assertThat(received.get()).isNotNull();
        assertThat(received.get().symbol()).isEqualTo("BTC/USDT");
        assertThat(received.get().last()).isEqualByComparingTo("50000");
    }

    /**
     * Phase 2 回归:回调(下游,如 DB/STOMP)抛异常不得被计为 WS 故障 → 不误降级、可恢复。
     * watchTicker 恒成功、回调恒抛错:应始终留在 WS 模式(从不 fetchTicker),而非被钉在轮询。
     */
    @Test
    void loop_whenCallbackThrows_doesNotDegradeToRestPolling() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchTicker(any())).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);
        var worker = new CcxtTickerWorker(
                ccxt,
                "BTC/USDT",
                "BTC/USDT",
                t -> {
                    calls.incrementAndGet();
                    latch.countDown();
                    throw new RuntimeException("db down");
                },
                Exchange.BINANCE,
                MarketType.SPOT);

        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();

        // 回调被调多次说明 WS 一直成功;且从未降级到 REST(fetchTicker 0 次)
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
        verify(ccxt, never()).fetchTicker(any());
    }

    /** 降级后 WS 恢复:探测成功回到 WS 模式(fetchTicker 不再增长,watchTicker 继续)。 */
    @Test
    void loop_whenWsRecoversAfterFallback_returnsToWsMode() throws Exception {
        var ccxt = mock(io.github.ccxt.Exchange.class);
        var failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new NetworkError("ws blocked"));
        // 前 2 次失败(达阈值降级),之后探测恢复成功
        when(ccxt.watchTicker(any()))
                .thenReturn(failed)
                .thenReturn(failed)
                .thenReturn(CompletableFuture.completedFuture(ccxtTicker()));
        when(ccxt.fetchTicker(any())).thenReturn(CompletableFuture.completedFuture(ccxtTicker()));

        CountDownLatch latch = new CountDownLatch(1);
        var worker = new CcxtTickerWorker(
                ccxt, "BTC/USDT", "BTC/USDT", t -> latch.countDown(), Exchange.BINANCE, MarketType.SPOT, 2, 20, 50);

        worker.start();
        // 降级 → 轮询 → 50ms 后探测恢复;最终收到回调(无论来自轮询还是恢复)
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // 等恢复探测发生(≥2 次 watchTicker:2 次失败 + 恢复探测)
        verify(ccxt, timeout(2_000).atLeast(3)).watchTicker(any());
        worker.stop();
    }
}
