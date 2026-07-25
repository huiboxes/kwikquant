-- V34: strategies 加 exchange_account_id —— strategy 关联当前部署账户
-- 去 UNIQUE(user_id, exchange)(V35)后,同用户同交易所可多账户(模拟盘+实盘并存);
-- strategy 启动时选账户(模拟盘/实盘),持久化本字段,worker token 绑 accountId(V34 配套)。
-- reconcile 重建 runner 读本字段(WorkerStatus 内存不持久化)。

ALTER TABLE strategies ADD COLUMN exchange_account_id BIGINT;

COMMENT ON COLUMN strategies.exchange_account_id IS '当前部署账户 ID(NULL=未关联;启动策略时填,worker token 绑定,reconcile 重建用)';

CREATE INDEX idx_strategies_exchange_account ON strategies (exchange_account_id)
    WHERE exchange_account_id IS NOT NULL;
