-- 回测逐 bar 进度上报(2026-07-25)
-- worker 在 event_loop 循环内每 200 bar 上报 processed_bars/total_bars,
-- Java 写入这两列 + 发 WS RUNNING 增量,前端右侧 tab 进度条据此渲染。
-- nullable:回测提交时未跑无值,worker 上报后才有;终态不重置(留末次进度供诊断)。
ALTER TABLE backtest_tasks ADD COLUMN processed_bars INT;
ALTER TABLE backtest_tasks ADD COLUMN total_bars INT;
