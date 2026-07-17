package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ccxt.Exchange;
import io.github.ccxt.exchanges.pro.Okx;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * OKX 交易所真实连通性验证（Smoke Test）。
 * <p>
 * 仅在设置了 CCXT_PROXY 环境变量时运行。CI 不设此变量则自动跳过。
 * <p>
 * 运行：CCXT_PROXY=socks5://127.0.0.1:7890 ./mvnw test -pl . -Dtest=OkxConnectivitySmokeTest -Pno-spotless
 */
@EnabledIfEnvironmentVariable(named = "CCXT_PROXY", matches = ".+")
class OkxConnectivitySmokeTest {

    private Exchange createOkx() {
        String proxyUrl = System.getenv("CCXT_PROXY");
        // CCXT Java httpsProxy 需要 http:// 前缀；从 socks5:// 格式转换
        String httpProxy = proxyUrl.replaceFirst("^socks5h?://", "http://");
        var okx = new Okx(Map.of("httpsProxy", httpProxy));
        return okx;
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetchTicker_shouldReturnRealBtcUsdtPrice() throws Exception {
        Exchange ex = createOkx();
        ex.loadMarkets().join();

        Object raw = ex.fetchTicker("BTC/USDT").join();
        assertThat(raw).isInstanceOf(Map.class);

        Map<String, Object> ticker = (Map<String, Object>) raw;
        Object last = ticker.get("last");
        assertThat(last).isNotNull();
        assertThat(((Number) last).doubleValue()).isPositive();

        System.out.println("✅ OKX BTC/USDT last=" + last
                + " bid=" + ticker.get("bid") + " ask=" + ticker.get("ask"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetchOHLCV_shouldReturnRealKlines() throws Exception {
        Exchange ex = createOkx();
        ex.loadMarkets().join();

        Object raw = ex.fetchOHLCV("BTC/USDT", "1h", null, 5).join();
        assertThat(raw).isInstanceOf(List.class);

        List<List<Object>> klines = (List<List<Object>>) raw;
        assertThat(klines).hasSize(5);

        System.out.println("✅ OKX BTC/USDT 1h klines (" + klines.size() + " bars):");
        for (var k : klines) {
            double close = ((Number) k.get(4)).doubleValue();
            assertThat(close).isPositive();
            System.out.printf("   O=%.2f H=%.2f L=%.2f C=%.2f V=%.4f%n",
                    ((Number) k.get(1)).doubleValue(),
                    ((Number) k.get(2)).doubleValue(),
                    ((Number) k.get(3)).doubleValue(),
                    close,
                    ((Number) k.get(5)).doubleValue());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetchOrderBook_shouldReturnRealDepth() throws Exception {
        Exchange ex = createOkx();
        ex.loadMarkets().join();

        Object raw = ex.fetchOrderBook("BTC/USDT", 5).join();
        assertThat(raw).isInstanceOf(Map.class);

        Map<String, Object> ob = (Map<String, Object>) raw;
        List<List<Double>> bids = (List<List<Double>>) ob.get("bids");
        List<List<Double>> asks = (List<List<Double>>) ob.get("asks");

        assertThat(bids).isNotEmpty();
        assertThat(asks).isNotEmpty();

        System.out.println("✅ OKX BTC/USDT order book: best bid="
                + bids.get(0).get(0) + "x" + bids.get(0).get(1)
                + " best ask=" + asks.get(0).get(0) + "x" + asks.get(0).get(1));
    }
}
