package com.kwikquant.shared.infra;

import io.github.ccxt.types.Balances;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CCXT Java 返回值兼容工具。把 CCXT Java 4.5.x 基类 {@code fetchBalance} 的多态返回
 * (Async 子类返 {@link CompletableFuture}、同步子类返 {@link Balances} 或 raw {@code Map})
 * 统一收敛为 raw {@code Map},使 {@code BalanceService.parseBalance} 等下游无需 instanceof/cast。
 *
 * <p>根因:CCXT Java 是 transpile 生成,Exchange 基类所有 unified method 签名统一为
 * {@code CompletableFuture<Object>}(javap 验证 4.5.59→4.5.71 不变),无法靠升级解除,故在此集中收敛。
 */
public final class CcxtResults {

    private CcxtResults() {}

    /**
     * 把 {@code fetchBalance} 多态返回收敛为 raw Map。
     *
     * <ul>
     *   <li>{@code CompletableFuture<...>}(AsyncExchange 子类) → {@code join} 后继续
     *   <li>{@link Balances}(同步子类) → 提取 {@code total}/{@code free}/{@code used}
     *   <li>raw {@code Map} → 透传
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> coerceBalances(Object raw) {
        Object r = raw;
        if (r instanceof CompletableFuture<?> cf) {
            r = cf.join();
        }
        if (r instanceof Balances b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", b.total);
            m.put("free", b.free);
            m.put("used", b.used);
            return m;
        }
        return (Map<String, Object>) r;
    }
}
