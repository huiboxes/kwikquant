package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyTemplate;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 官方策略模板目录。模板随版本发布：元数据在此声明，策略源码放 classpath
 * {@code strategy-templates/<key>.py}（与 key 一一对应），启动时一次性载入内存。
 *
 * <p>不落库：官方模板是产品资产而非用户数据，代码评审 + 版本发布即变更流程；
 * 未来若开放用户共享模板（社区），再引入持久化，本类仍是官方目录的唯一入口。
 *
 * <p>启动 fail-fast：资源缺失 / 源码为空 / 未定义 {@code on_bar} 直接抛异常阻止启动，
 * 不让坏模板流入 fork 链路。
 */
@Component
public class StrategyTemplateCatalog {

    private static final String RESOURCE_DIR = "strategy-templates/";

    /** 模板元数据（展示顺序即列表顺序，入门款在前）。全部 SPOT（回测 Gateway 拒 PERP）。 */
    private static final List<TemplateMeta> METADATA = List.of(
            new TemplateMeta(
                    "ma-double-cross",
                    "均线双金叉",
                    "MA5/MA10/MA20 双重确认金叉做多、死叉平仓。入门首选：逻辑最直白的趋势策略",
                    List.of("趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "1h",
                    90),
            new TemplateMeta(
                    "donchian-breakout",
                    "唐奇安通道突破",
                    "突破前 19 根最高做多、跌破前 9 根最低平仓。海龟交易法同源的 CTA 趋势跟踪",
                    List.of("趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "4h",
                    180),
            new TemplateMeta(
                    "fixed-grid",
                    "固定网格",
                    "围绕 50 根均线基线每 1% 一格，下穿买上穿卖。震荡市低买高卖，限 5 格防单边",
                    List.of("网格", "均值回归"),
                    "BTC/USDT",
                    "OKX",
                    "1h",
                    90),
            new TemplateMeta(
                    "rsi-reversal",
                    "RSI 超卖反转",
                    "RSI(14) 跌破 30 超卖做多、升破 70 超买平仓。博反弹的均值回归策略",
                    List.of("均值回归"),
                    "ETH/USDT",
                    "OKX",
                    "1h",
                    90),
            new TemplateMeta(
                    "bollinger-reversion",
                    "布林带回归",
                    "触及 MA20−2σ 下轨做多、触及上轨平仓。赌价格回归中轨的统计套利骨架",
                    List.of("均值回归"),
                    "ETH/USDT",
                    "OKX",
                    "1h",
                    90),
            new TemplateMeta(
                    "macd-trend",
                    "MACD 趋势跟踪",
                    "DIF 金叉信号线做多、死叉平仓。比均线交叉噪音更少的中期趋势策略",
                    List.of("趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "4h",
                    180),
            new TemplateMeta(
                    "turtle-breakout",
                    "海龟突破（日线）",
                    "突破 20 日最高做多、跌破 10 日最低离场。信号少、持仓久的长线趋势系统",
                    List.of("趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "1d",
                    365),
            new TemplateMeta(
                    "ema-scalper",
                    "短周期均线动量",
                    "1 分钟 EMA9/EMA21 交叉，捕捉日内短促动量。信号频繁、费用敏感，适合二次开发",
                    List.of("日内", "趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "1m",
                    30),
            new TemplateMeta(
                    "dual-thrust",
                    "Dual Thrust 区间突破",
                    "经典日内突破：收盘价冲上 锚点+K×Range 做多、跌破离场。Range 度量波动能量",
                    List.of("日内", "趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "15m",
                    60),
            new TemplateMeta(
                    "keltner-breakout",
                    "肯特纳通道突破",
                    "突破 EMA20+2×ATR 上轨做多、跌回 EMA 下方平仓。ATR 通道对跳空更稳健",
                    List.of("趋势跟踪"),
                    "ETH/USDT",
                    "OKX",
                    "1h",
                    90),
            new TemplateMeta(
                    "roc-momentum",
                    "变动率动量",
                    "4 天涨幅超 2% 顺势做多、涨幅归零离场。强者恒强的动量骨架",
                    List.of("趋势跟踪"),
                    "BTC/USDT",
                    "OKX",
                    "4h",
                    180),
            new TemplateMeta(
                    "vwap-reversion",
                    "VWAP 偏离回归",
                    "价格偏离滚动 VWAP 超 0.8% 反向做、回归后离场。锚定成交量加权成本区",
                    List.of("日内", "均值回归"),
                    "ETH/USDT",
                    "OKX",
                    "15m",
                    60));

    private final Map<String, StrategyTemplate> templates = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        for (TemplateMeta meta : METADATA) {
            if (templates.containsKey(meta.key())) {
                throw new IllegalStateException("duplicate strategy template key: " + meta.key());
            }
            String source = readSource(meta.key());
            templates.put(
                    meta.key(),
                    new StrategyTemplate(
                            meta.key(),
                            meta.name(),
                            meta.description(),
                            meta.tags(),
                            meta.symbol(),
                            meta.exchange(),
                            meta.intervalValue(),
                            "{}",
                            meta.backtestWindowDays(),
                            source));
        }
    }

    /** 全部模板（目录声明顺序）。 */
    public List<StrategyTemplate> all() {
        return List.copyOf(templates.values());
    }

    /** 按 key 取模板，不存在返回 null（调用方负责抛 {@code TemplateNotFoundException}）。 */
    public StrategyTemplate get(String key) {
        return templates.get(key);
    }

    private static String readSource(String key) {
        String path = RESOURCE_DIR + key + ".py";
        ClassPathResource resource = new ClassPathResource(path);
        String source;
        try {
            source = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("strategy template resource missing: " + path, e);
        }
        if (source.isBlank()) {
            throw new IllegalStateException("strategy template source is empty: " + path);
        }
        if (!source.contains("def on_bar")) {
            throw new IllegalStateException("strategy template source must define on_bar(bar, ctx): " + path);
        }
        return source;
    }

    /** 模板元数据（源码在资源文件，启动时按 key 拼装为完整 {@link StrategyTemplate}）。 */
    private record TemplateMeta(
            String key,
            String name,
            String description,
            List<String> tags,
            String symbol,
            String exchange,
            String intervalValue,
            int backtestWindowDays) {}
}
