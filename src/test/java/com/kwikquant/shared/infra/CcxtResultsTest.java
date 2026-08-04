package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ccxt.types.Balances;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * CcxtResults 单测。覆盖 {@link CcxtResults#coerceBalances} 的 4 种入参,对应 CCXT Java 4.5.x
 * 基类 {@code fetchBalance} 的多态返回:Async 子类返 {@code CompletableFuture},同步子类返
 * {@link Balances} 或 raw {@code Map}。工具须统一收敛为 raw Map 供 {@code parseBalance} 用。
 */
class CcxtResultsTest {

    @Test
    void coerceBalances_balances_extractsTotalFreeUsed() {
        Balances b = new Balances(new HashMap<>());
        b.total = Map.of("USDT", 100.0);
        b.free = Map.of("USDT", 60.0);
        b.used = Map.of("USDT", 40.0);

        Map<String, Object> result = CcxtResults.coerceBalances(b);

        assertThat(result.get("total")).isEqualTo(Map.of("USDT", 100.0));
        assertThat(result.get("free")).isEqualTo(Map.of("USDT", 60.0));
        assertThat(result.get("used")).isEqualTo(Map.of("USDT", 40.0));
    }

    @Test
    void coerceBalances_completableFutureBalances_joinsThenExtracts() {
        Balances b = new Balances(new HashMap<>());
        b.total = Map.of("BTC", 1.0);
        b.free = Map.of("BTC", 1.0);
        b.used = Map.of();

        Map<String, Object> result = CcxtResults.coerceBalances(CompletableFuture.completedFuture(b));

        assertThat(result.get("total")).isEqualTo(Map.of("BTC", 1.0));
        assertThat(result.get("free")).isEqualTo(Map.of("BTC", 1.0));
    }

    @Test
    void coerceBalances_map_passesThrough() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("total", Map.of("USDT", 100.0));
        raw.put("free", Map.of("USDT", 60.0));
        raw.put("used", Map.of("USDT", 40.0));

        Map<String, Object> result = CcxtResults.coerceBalances(raw);

        assertThat(result).isSameAs(raw);
    }

    @Test
    void coerceBalances_completableFutureMap_joinsThenPassesThrough() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("total", Map.of("ETH", 5.0));

        Map<String, Object> result = CcxtResults.coerceBalances(CompletableFuture.completedFuture(raw));

        assertThat(result).isSameAs(raw);
    }
}
