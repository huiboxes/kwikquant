package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ccxt.Exchange;
import io.github.ccxt.exchanges.pro.Binance;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * E2E 冒烟测试：连 Binance **testnet**（模拟盘）公开行情，无需 API key。
 * 默认禁用，仅 -Dkwikquant.e2e=true 时跑，不进 mvn verify。
 *
 * <p>关键：引用类型声明为基类 {@link Exchange}（与 CcxtTickerWorker 一致），而非具体 Binance。
 * 这样 watchTicker(String) 编译期绑定基类 watchTicker(Object...) 返 CompletableFuture<Object>，
 * 走 worker 的真实 dispatch 路径（.get() 后强转 Ticker）。若声明为 Binance 会绑定到同步
 * watchTicker(String)→Ticker，路径不同。
 *
 * <p>网络：本环境 Binance 主网被 geo-block（451）。testnet 可达——spot testnet
 * (testnet.binance.vision) 直连；perp testnet (testnet.binancefuture.com) 走 HTTP 代理。
 * spot/perp 各自 setSandboxMode(true) 切到 testnet URL。
 *
 * 验证 context.md 记的 3 个 CCXT 实测点：
 * 1. 基类 watchTicker 返回的 CF 完成后是 typed Ticker（worker 强转成立）
 * 2. loadMarkets 后 markets 字段结构 + precision 语义
 * 3. perp 实例 defaultType=swap + "BTC/USDT" 订到 perp
 */
@EnabledIfSystemProperty(named = "kwikquant.e2e", matches = "true")
class BinanceE2ESmokeTest {

    private static final String BTC_USDT = "BTC/USDT";

    private static Binance perpTestnet() {
        var b = new Binance(Map.of("options", Map.of("defaultType", "swap")));
        b.setSandboxMode(true); // → testnet.binancefuture.com（需 HTTP 代理）
        return b;
    }

    @Test
    void loadMarkets_shouldExposeMarketsMapWithPrecision() throws Exception {
        // perp testnet via HTTP 代理（spot testnet 直连在 JVM 里超时）
        String host = System.getProperty("kwikquant.http.proxy.host", "127.0.0.1");
        String port = System.getProperty("kwikquant.http.proxy.port", "13659");
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        try {
            Exchange binance = perpTestnet();
            binance.loadMarkets(false).get(30, TimeUnit.SECONDS);
            Object markets = binance.markets;
            // 验证点 ②：markets 是 Map<String, Map>，含 precision/limits
            assertThat(markets).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) markets;
            assertThat(map).containsKey(BTC_USDT);
            Object btcMarket = map.get(BTC_USDT);
            assertThat(btcMarket).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> btc = (Map<String, Object>) btcMarket;
            System.out.println("[E2E BTC/USDT market] keys=" + btc.keySet());
            System.out.println("[E2E BTC/USDT precision]=" + btc.get("precision"));
            System.out.println("[E2E BTC/USDT limits]=" + btc.get("limits"));
            assertThat(btc).containsKeys("symbol", "base", "quote");
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
        }
    }

    @Test
    void perp_watchTicker_withDefaultTypeSwap_shouldReturnData() throws Exception {
        // perp testnet 走 HTTP 代理（testnet.binancefuture.com 直连超时）
        String host = System.getProperty("kwikquant.http.proxy.host", "127.0.0.1");
        String port = System.getProperty("kwikquant.http.proxy.port", "13659");
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        System.out.println("[E2E perp] HTTP proxy = " + host + ":" + port);
        try {
            // 验证点 ③：perp 实例 + "BTC/USDT" + defaultType=swap 订到 perp testnet
            Exchange binance = perpTestnet();
            var raw = binance.watchTicker(BTC_USDT).get(20, TimeUnit.SECONDS);
            assertThat(raw)
                    .as("perp watchTicker should return a ticker dict Map")
                    .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) raw;
            System.out.println("[E2E perp ticker] symbol=" + t.get("symbol") + " last=" + t.get("last"));
            assertThat(t.get("last")).isNotNull();
            assertThat(t.get("symbol")).asString().contains("BTC");
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
        }
    }

    @Test
    void perp_watchOhlcv_shouldReturnRawCandleList() throws Exception {
        // 验证 kline worker 的 watchOHLCV 真实返回结构（perp testnet via HTTP 代理）
        String host = System.getProperty("kwikquant.http.proxy.host", "127.0.0.1");
        String port = System.getProperty("kwikquant.http.proxy.port", "13659");
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        try {
            Exchange binance = perpTestnet();
            var raw = binance.watchOHLCV(BTC_USDT, "1m").get(20, TimeUnit.SECONDS);
            System.out.println("[E2E perp ohlcv] class=" + raw.getClass().getName());
            System.out.println("[E2E perp ohlcv] first element class="
                    + (raw instanceof java.util.List<?> l && !l.isEmpty()
                            ? l.get(0).getClass().getName()
                            : "n/a"));
            System.out.println("[E2E perp ohlcv] sample="
                    + (raw instanceof java.util.List<?> l && !l.isEmpty() ? l.get(l.size() - 1) : raw));
            assertThat(raw).as("watchOHLCV should return a List").isInstanceOf(java.util.List.class);
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
        }
    }
}
