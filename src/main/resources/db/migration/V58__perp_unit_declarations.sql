-- V58: PERP 数量单位声明 + 僵尸列清理
-- 单位契约:域内/DB 规范单位=币数量(base coin);合约张数(OKX sz/pos/fillSz)只在交易所边界
-- 适配器经 contractSize 换算(出站 PerpMath.toContracts / 回流 PerpMath.toCoin),不跨层、不落库。
-- PAPER 账本历来币语义自洽,无存量数据迁移;LIVE PERP 尚无成交存量(上线前)。

COMMENT ON COLUMN orders.amount IS '下单数量,单位=币数量(base coin);PERP 张数由交易所边界按 contractSize 换算';
COMMENT ON COLUMN orders.filled_qty IS '累计成交数量,单位=币数量(base coin)';
COMMENT ON COLUMN fills.qty IS '成交数量,单位=币数量(base coin)';
COMMENT ON COLUMN positions.qty IS '持仓数量,单位=币数量(base coin)';
COMMENT ON COLUMN funding_settlements.qty_at_settle IS '结算时持仓量,单位=币数量(base coin;取本地 position.qty,交易所 bill.posBal 单位不可核实禁用)';

-- 杠杆上限口径修正:V44 注释写 1-125,OKX 实测 per-symbol 上限 100(且以交易所声明为准,fail-closed)
COMMENT ON COLUMN strategies.leverage IS '合约杠杆倍数: PERP 1-100(不超交易所 per-symbol 声明); SPOT null';
COMMENT ON COLUMN orders.leverage IS '杠杆倍数(PERP,不超交易所 per-symbol 声明);SPOT NULL';
COMMENT ON COLUMN positions.leverage IS '杠杆倍数(PERP,不超交易所 per-symbol 声明);SPOT NULL';

-- V31 遗留僵尸列清理(V32 已声明不读写:逐仓强平判 frozen_amount + 派生 unrealizedPnl,
-- markPrice 走内存 ConcurrentMap 不入 DB;Java 侧零引用已核实)
ALTER TABLE positions DROP COLUMN margin_balance;
ALTER TABLE positions DROP COLUMN mark_price;
