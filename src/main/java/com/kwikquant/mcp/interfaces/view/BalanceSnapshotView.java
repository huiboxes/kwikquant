package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.BalanceSnapshot.CurrencyBalance;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP {@code get_balances} 工具返回的余额投影。剥离 service 层 {@link BalanceSnapshot} 的未来内部字段，
 * 只暴露 currencies Map（{@link CurrencyBalanceView} free/used/total）。模块边界隔离：service record 变更
 * 不直接冲击 MCP Agent 契约。
 */
public record BalanceSnapshotView(Map<String, CurrencyBalanceView> currencies) {
    public static BalanceSnapshotView from(BalanceSnapshot snapshot) {
        if (snapshot == null || snapshot.currencies() == null) {
            return new BalanceSnapshotView(Map.of());
        }
        return new BalanceSnapshotView(snapshot.currencies().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> CurrencyBalanceView.from(e.getValue()))));
    }

    public record CurrencyBalanceView(BigDecimal free, BigDecimal used, BigDecimal total) {
        public static CurrencyBalanceView from(CurrencyBalance c) {
            if (c == null) {
                return new CurrencyBalanceView(null, null, null);
            }
            return new CurrencyBalanceView(c.free(), c.used(), c.total());
        }
    }
}
