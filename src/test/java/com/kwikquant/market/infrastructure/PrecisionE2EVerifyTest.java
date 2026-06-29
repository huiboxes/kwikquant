package com.kwikquant.market.infrastructure;

import io.github.ccxt.Exchange;
import io.github.ccxt.exchanges.pro.Bitget;
import io.github.ccxt.exchanges.pro.Okx;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 验证 CCXT Java precision 字段语义：是小数位数还是 tick 值。
 * 用 OKX 和 Bitget（代理可达），Binance 主网被 geo-block。
 */
@EnabledIfSystemProperty(named = "kwikquant.e2e", matches = "true")
class PrecisionE2EVerifyTest {

    private static final String PROXY_HOST = System.getProperty("kwikquant.http.proxy.host", "127.0.0.1");
    private static final String PROXY_PORT = System.getProperty("kwikquant.http.proxy.port", "13659");
    private static final String PROXY_URL = "http://" + PROXY_HOST + ":" + PROXY_PORT;

    @Test
    void verifyOkxPrecision() throws Exception {
        Exchange okx = new Okx(Map.of("httpsProxy", PROXY_URL));
        okx.loadMarkets(false).get(30, TimeUnit.SECONDS);

        Object markets = okx.markets;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) markets;

        printPrecision("OKX", "BTC/USDT", map.get("BTC/USDT"));
        printPrecision("OKX", "ETH/USDT", map.get("ETH/USDT"));
        printPrecision("OKX", "SHIB/USDT", map.get("SHIB/USDT"));
    }

    @Test
    void verifyBitgetPrecision() throws Exception {
        Exchange bitget = new Bitget(Map.of("httpsProxy", PROXY_URL));
        bitget.loadMarkets(false).get(30, TimeUnit.SECONDS);

        Object markets = bitget.markets;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) markets;

        printPrecision("Bitget", "BTC/USDT", map.get("BTC/USDT"));
        printPrecision("Bitget", "ETH/USDT", map.get("ETH/USDT"));
    }

    @SuppressWarnings("unchecked")
    private static void printPrecision(String exchange, String symbol, Object marketRaw) {
        if (!(marketRaw instanceof Map<?, ?> m)) {
            System.out.println("[" + exchange + " " + symbol + "] market not found or not a Map");
            return;
        }
        Object precision = m.get("precision");
        Object limits = m.get("limits");

        System.out.println("=== " + exchange + " " + symbol + " ===");
        System.out.println("  precision (raw): " + precision);
        System.out.println("  precision class: " + (precision != null ? precision.getClass().getName() : "null"));

        if (precision instanceof Map<?, ?> p) {
            System.out.println("  precision.price = " + p.get("price") + " (class: "
                    + (p.get("price") != null ? p.get("price").getClass().getName() : "null") + ")");
            System.out.println("  precision.amount = " + p.get("amount") + " (class: "
                    + (p.get("amount") != null ? p.get("amount").getClass().getName() : "null") + ")");
        }

        if (limits instanceof Map<?, ?> l) {
            if (l.get("amount") instanceof Map<?, ?> amt) {
                System.out.println("  limits.amount.min = " + amt.get("min"));
                System.out.println("  limits.amount.max = " + amt.get("max"));
            }
            if (l.get("price") instanceof Map<?, ?> pr) {
                System.out.println("  limits.price.min = " + pr.get("min"));
                System.out.println("  limits.price.max = " + pr.get("max"));
            }
            if (l.get("cost") instanceof Map<?, ?> cost) {
                System.out.println("  limits.cost.min = " + cost.get("min"));
            }
        }
        System.out.println();
    }
}
