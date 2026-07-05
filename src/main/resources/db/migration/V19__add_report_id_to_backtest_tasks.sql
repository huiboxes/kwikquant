-- 契约改动 B（wave9-contract）：backtest_tasks 加 report_id 列，task→report 导航桥梁。
-- COMPLETED 时由 BacktestExecutionGateway 回填（reportService.submitBacktestResult 返 BacktestReport.getId()）。
-- 前端轮询 GET /backtests/:id 到 COMPLETED 后拿 reportId 直查 GET /reports/{reportId}。
ALTER TABLE backtest_tasks ADD COLUMN report_id BIGINT REFERENCES backtest_reports(id) ON DELETE SET NULL;

COMMENT ON COLUMN backtest_tasks.report_id IS '回测报告 ID（COMPLETED 时回填，task→report 导航桥梁，契约改动 B）';

CREATE INDEX idx_backtest_tasks_report_id ON backtest_tasks (report_id) WHERE report_id IS NOT NULL;
