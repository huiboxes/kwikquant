-- 组合(多标的)回测支持:
--
-- 1) backtest_tasks:symbols JSONB(组合任务的标的列表快照,如 ["BTC/USDT","ETH/USDT"])。
--    symbol 列保持 NOT NULL:单标的任务存该标的,组合任务存逗号拼接的多标的列表(与
--    backtest_reports.symbol 口径一致)。是否组合以 symbols 是否为 NULL 判别
--    (单标的任务 symbols = NULL)。symbol 加宽到 1024 以容纳组合拼接
--    (上限 20 标的×30 字符+19 逗号=619,留足余量)。
-- 2) backtest_reports:symbol 加宽到 1024(组合报告存逗号拼接的多标的列表供展示);
--    symbols JSONB 存结构化标的列表;final_positions JSONB 存分标的终仓快照
--    ([{"symbol","qty","avgPrice"}],单标的报告为 NULL)。
-- 3) trade_records:symbol 标记每笔成交所属标的(组合报告逐笔分标的;存量单标的报告保持
--    NULL,标的由所属报告的 symbol 隐含)。

ALTER TABLE backtest_tasks ALTER COLUMN symbol TYPE VARCHAR(1024);
ALTER TABLE backtest_tasks ADD COLUMN symbols JSONB;

COMMENT ON COLUMN backtest_tasks.symbol IS '回测交易对(单标的)或逗号拼接的多标的列表(组合回测)';
COMMENT ON COLUMN backtest_tasks.symbols IS '组合回测标的列表快照(单标的任务为 NULL)';

ALTER TABLE backtest_reports ALTER COLUMN symbol TYPE VARCHAR(1024);
ALTER TABLE backtest_reports ADD COLUMN symbols JSONB;
ALTER TABLE backtest_reports ADD COLUMN final_positions JSONB;

COMMENT ON COLUMN backtest_reports.symbols IS '组合回测标的列表(单标的报告为 NULL)';
COMMENT ON COLUMN backtest_reports.final_positions IS '分标的终仓快照 [{symbol,qty,avgPrice}](组合报告,单标的为 NULL)';

ALTER TABLE trade_records ADD COLUMN symbol VARCHAR(30);

COMMENT ON COLUMN trade_records.symbol IS '成交所属标的(组合报告逐笔标记;单标的报告为 NULL)';

CREATE INDEX idx_trade_records_report_symbol ON trade_records (report_id, symbol);
