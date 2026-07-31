ALTER TABLE fills ADD COLUMN realized_pnl_delta NUMERIC(20, 8) NOT NULL DEFAULT 0;

COMMENT ON COLUMN fills.realized_pnl_delta IS
    '本笔成交的已实现盈亏增量(平仓 PnL;开仓/加仓=0)。供 DAILY_LOSS_LIMIT 风控按日汇总,替代旧净现金流口径';
