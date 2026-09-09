-- PERP 回测报告落库(docs/perp-backtest-spec.md §8.1):
--
-- 1) backtest_reports:market_type 区分 SPOT/PERP 报告(存量行默认 SPOT,决定指标配对路径);
--    liquidation_model 仅 PERP 非空(强制声明近似模型,如 BAR_EXTREME_APPROX)。
--    section8 warnings 不设独立列——worker 已把同一数组嵌入 params._kwikquant(单一真相源),
--    随 params JSONB 落库,前端可信度提示与 AI 解读既有链路消费。
-- 2) trade_records:position_effect 仅 PERP 行非空(OPEN_LONG|OPEN_SHORT|CLOSE_LONG|CLOSE_SHORT,
--    配对按净持仓 signed FIFO,side 是派生量不能用于配对);liquidation 标记强平成交行。

ALTER TABLE backtest_reports ADD COLUMN market_type VARCHAR(8) NOT NULL DEFAULT 'SPOT';
ALTER TABLE backtest_reports ADD COLUMN liquidation_model VARCHAR(32);

COMMENT ON COLUMN backtest_reports.market_type IS '报告市场类型(SPOT|PERP),存量默认 SPOT';
COMMENT ON COLUMN backtest_reports.liquidation_model IS 'PERP 强平近似模型声明(仅 PERP 非空,如 BAR_EXTREME_APPROX)';

ALTER TABLE trade_records ADD COLUMN position_effect VARCHAR(16);
ALTER TABLE trade_records ADD COLUMN liquidation BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN trade_records.position_effect IS 'PERP 四向意图(OPEN_LONG|OPEN_SHORT|CLOSE_LONG|CLOSE_SHORT;SPOT 行为 NULL)';
COMMENT ON COLUMN trade_records.liquidation IS '强平成交行标记(PERP bar 极值近似,仅 PERP 可为 true)';
