package com.kwikquant.strategy.domain;

import java.util.List;

/**
 * 官方策略模板（不可变值对象）。模板目录随版本发布（代码内置 catalog + classpath 源码资源，
 * 不落库），fork 时复制为用户自己的 DRAFT 策略并直接发布模板源码。
 *
 * <p>全部 SPOT（回测 Gateway 拒 PERP）。{@code backtestWindowDays} 是模板推荐的首次回测窗口：
 * fork 自动首回测按 {@code [now - windowDays, now]}（对齐 interval 网格）提交；取值须保证
 * bar 数不超过 {@code kwikquant.backtest.max-bars}（由 catalog 测试守护）。
 *
 * <p>{@code sourceCode} 是可直接运行的函数式策略源码（顶层 {@code def on_bar(bar, ctx)}，
 * 平台 ctx API：history/position/place_order/log），fork 后用户可自行修改。
 */
public record StrategyTemplate(
        String key,
        String name,
        String description,
        List<String> tags,
        String symbol,
        String exchange,
        String intervalValue,
        String parameters,
        int backtestWindowDays,
        String sourceCode) {

    public StrategyTemplate {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("template key must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("template name must not be blank");
        }
        if (backtestWindowDays <= 0) {
            throw new IllegalArgumentException("template backtestWindowDays must be positive");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
