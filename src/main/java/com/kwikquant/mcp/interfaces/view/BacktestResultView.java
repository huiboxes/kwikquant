package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;

/**
 * MCP {@code run_backtest} 工具返回的回测结果视图。双模式共用：
 * <ul>
 *   <li>COMPLETED：{@code result} = {@link BacktestTask#getResult()}（§8 标准协议 JSON String，含 metrics/trades/equityCurve）
 *   <li>RUNNING（60s 轮询超时降级）：{@code hint} 引导 Agent 再次调 {@code run_backtest(taskId=...)} 续查
 *   <li>FAILED：{@code errorMessage} = task 失败原因
 * </ul>
 *
 * <p>状态机：BacktestTask 无 reportId 字段（结果存 task.result JSON），故不调 ReportService.getById，
 * 直接用 task.result。list_backtests 工具才走 ReportService（BacktestReport 派生数据）。
 */
public record BacktestResultView(long taskId, String status, String result, String errorMessage, String hint) {
    public static BacktestResultView from(BacktestTask t) {
        return new BacktestResultView(t.getId(), t.getStatus().name(), t.getResult(), t.getErrorMessage(), null);
    }

    public static BacktestResultView running(long taskId, String hint) {
        return new BacktestResultView(taskId, BacktestTaskStatus.RUNNING.name(), null, null, hint);
    }
}
