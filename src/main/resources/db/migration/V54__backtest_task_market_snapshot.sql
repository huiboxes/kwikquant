-- 回测任务行情快照加固(V54):
-- 1) 持久化 market_type:提交时从策略冻结,消除"排队期间改策略 marketType → 执行时用不同市场"的
--    快照漂移(此前 marketType 仅在 BacktestExecutionGateway 运行时从当前策略派生)。
-- 2) 部分索引加速 per-user 活动任务配额计数(countActiveByUser),配合 BacktestQuotaGuard 的
--    pg_advisory_xact_lock 事务锁消除 count-then-insert 并发竞态。
ALTER TABLE backtest_tasks ADD COLUMN market_type VARCHAR(10);

UPDATE backtest_tasks bt
SET market_type = COALESCE(s.market_type, 'SPOT')
FROM strategies s
WHERE bt.strategy_id = s.id;

-- 兜底:理论上 FK 保证每行都能 join 上策略,防御性回填
UPDATE backtest_tasks SET market_type = 'SPOT' WHERE market_type IS NULL;

ALTER TABLE backtest_tasks ALTER COLUMN market_type SET NOT NULL;

ALTER TABLE backtest_tasks
    ADD CONSTRAINT chk_backtest_tasks_market_type CHECK (market_type IN ('SPOT', 'PERP'));

COMMENT ON COLUMN backtest_tasks.market_type IS '市场类型快照(提交时冻结,SPOT/PERP;Worker 拉数据以此为准)';

CREATE INDEX IF NOT EXISTS idx_backtest_tasks_user_active
    ON backtest_tasks (user_id)
    WHERE status IN ('PENDING', 'RUNNING');
