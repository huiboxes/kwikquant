package com.kwikquant.mcp.interfaces.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;

/**
 * MCP {@code run_backtest} 工具返回的回测结果视图。双模式共用：
 * <ul>
 *   <li>COMPLETED：{@code result} = task.result 的 <b>{totalPnl, tradeCount} 摘要</b>
 *       （不是完整结果 JSON；完整指标/成交/曲线在报告域，用 {@code reportId} 经
 *       {@code list_backtests} / {@code compare_backtests} 获取）。totalPnl 读出侧转
 *       decimal string（与 MCP 金额纪律一致，库存量形态不动）</li>
 *   <li>RUNNING（60s 轮询超时降级）：{@code hint} 引导 Agent 再次调 {@code run_backtest(taskId=...)} 续查</li>
 *   <li>FAILED：{@code errorMessage} = task 失败原因</li>
 * </ul>
 *
 * <p>{@code marketType}（SPOT/PERP 任务快照）让 Agent 无需追查即可声明结果口径：PERP 报告
 * 的强平是 bar 极值近似、winRate/profitFactor 是毛配对（docs/perp-backtest-spec.md §4.2/§8.2），
 * 缺了它 Agent 会语气确定地转述带近似口径的数字。{@code symbols} 是组合（多标的）任务的
 * 标的列表（单标的任务为 null）——Agent 转述组合结果时需要它声明覆盖范围。
 */
public record BacktestResultView(
        long taskId,
        String status,
        String marketType,
        java.util.List<String> symbols,
        Long reportId,
        String result,
        String errorMessage,
        String hint) {

    // USE_BIG_DECIMAL_FOR_FLOATS:totalPnl 以 DecimalNode 解析,避免 DoubleNode 往返在
    // ≥17 位有效数字(10^9 级本金 × 8 位小数)时丢末位——与本视图宣称的金额纪律自洽
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static BacktestResultView from(BacktestTask t) {
        return new BacktestResultView(
                t.getId(),
                t.getStatus().name(),
                t.getMarketType(),
                t.getSymbols(),
                t.getReportId(),
                decimalizeTotalPnl(t.getResult()),
                t.getErrorMessage(),
                null);
    }

    public static BacktestResultView running(long taskId, String hint) {
        return new BacktestResultView(taskId, BacktestTaskStatus.RUNNING.name(), null, null, null, null, null, hint);
    }

    /** result 摘要里的 totalPnl JSON number → decimal string(解析失败原样返回,诊断字段不设防)。 */
    private static String decimalizeTotalPnl(String result) {
        if (result == null || result.isBlank()) {
            return result;
        }
        try {
            JsonNode node = MAPPER.readTree(result);
            if (node instanceof ObjectNode obj
                    && obj.has("totalPnl")
                    && obj.get("totalPnl").isNumber()) {
                obj.put("totalPnl", obj.get("totalPnl").decimalValue().toPlainString());
                return MAPPER.writeValueAsString(obj);
            }
        } catch (Exception e) { // noqa: 摘要形态异常不阻断结果透出(诊断字段,原样透出)
        }
        return result;
    }
}
