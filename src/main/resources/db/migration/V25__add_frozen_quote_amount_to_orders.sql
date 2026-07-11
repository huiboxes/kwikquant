-- 模拟盘 BUY 单挂单时冻结的 quote 金额，用真实价格（LIMIT 用限价，MARKET 用当时 ticker 估价）
-- 计算；成交时按当时的成交价（可能是 ask/bid，跟冻结价不同）计算实际成本。之前 applyFill 直接拿
-- 成交价当"冻结时解冻的量"用，MARKET 单冻结价和成交价系统性不同，导致 paper_balances.used 每笔
-- 都残留一点漂移（长期运行会累积成明显的负数，free 虽然总量 total 依然守恒，但 free/used 的拆分
-- 失真）。持久化真实冻结量，成交时按这个值精确解冻，差价走 free 调整，不再残留进 used。
ALTER TABLE orders ADD COLUMN frozen_quote_amount NUMERIC(20, 8);

COMMENT ON COLUMN orders.frozen_quote_amount IS
    '模拟盘 BUY 单挂单时冻结的 quote 金额（仅 BUY 单设置，SELL 单冻结的是 base 数量，无价格漂移问题不需要这列）。';
