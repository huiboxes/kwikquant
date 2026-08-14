package com.kwikquant.shared.types;

import java.util.EnumSet;
import java.util.Set;

/**
 * MCP PAT 权限域(粗粒度 5 档,不做 per-tool)。签发默认仅 READ,写权限显式开通——最小权限原则,
 * PAT 进 agent 配置文件后不自动具备动钱能力。
 *
 * <ul>
 *   <li>{@link #READ} — 全部只读工具(行情/账户/持仓/挂单/风控规则/回测列表/对比)
 *   <li>{@link #BACKTEST} — run_backtest(消耗算力,不动钱)
 *   <li>{@link #TRADE} — submit_order/cancel_order/close_position/start_paper_trading(仅模拟盘直接免确认)
 *   <li>{@link #LIVE} — start_live_trading(实盘启动,最高危)
 *   <li>{@link #RISK} — set_risk_rules/emergency_stop(全局风控面)
 * </ul>
 *
 * <p>注:TRADE 覆盖的写工具对**实盘账户**仍需两阶段 confirmToken(见 McpConfirmTokenService),
 * scope 与确认是两层独立防护。
 */
public enum McpTokenScope {
    READ,
    BACKTEST,
    TRADE,
    LIVE,
    RISK;

    /** 全部 scope(存量 PAT 迁移默认值,保持向后兼容)。 */
    public static final Set<McpTokenScope> ALL = EnumSet.allOf(McpTokenScope.class);

    /** 新签发默认最小权限。 */
    public static final Set<McpTokenScope> DEFAULT = EnumSet.of(READ);

    /** 大小写不敏感解析;非法值抛 IllegalArgumentException(调用方转校验错误)。 */
    public static McpTokenScope parse(String raw) {
        return valueOf(raw.trim().toUpperCase());
    }

    /** 从逗号分隔存储格式解析(DB scopes 列)。空/空白 → DEFAULT。 */
    public static Set<McpTokenScope> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return DEFAULT;
        }
        Set<McpTokenScope> scopes = EnumSet.noneOf(McpTokenScope.class);
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                scopes.add(parse(part));
            }
        }
        return scopes;
    }

    /** 序列化为逗号分隔存储格式(枚举名稳定序)。 */
    public static String toCsv(Set<McpTokenScope> scopes) {
        StringBuilder sb = new StringBuilder();
        for (McpTokenScope s : values()) { // values() 序稳定,避免 EnumSet 迭代序依赖
            if (scopes.contains(s)) {
                if (!sb.isEmpty()) sb.append(',');
                sb.append(s.name());
            }
        }
        return sb.toString();
    }
}
