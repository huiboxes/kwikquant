-- V31: 合约(PERP)下单能力建设 —— positions/orders 加合约列 + positions 唯一索引扩合约维度
-- SPOT 持仓/订单这些列 NULL,向后兼容(SPOT 下单路径不受影响)。
-- 设计见 .evo/tasks/trading-perp/tech-design.md §2.1。

-- positions 加合约列
ALTER TABLE positions ADD COLUMN leverage          INT            ;  -- 杠杆倍数,SPOT NULL
ALTER TABLE positions ADD COLUMN margin_mode      VARCHAR(8)     ;  -- ISOLATED/CROSS,SPOT NULL
ALTER TABLE positions ADD COLUMN position_side    VARCHAR(8)     ;  -- LONG/SHORT(合约);SPOT NULL(side 字符串 long/short 保留兼容)
ALTER TABLE positions ADD COLUMN liquidation_price NUMERIC(20,8) ;  -- 强平价(逐仓,简化公式算)
ALTER TABLE positions ADD COLUMN mark_price       NUMERIC(20,8)  ;  -- 标记价(强平判定用)
ALTER TABLE positions ADD COLUMN maint_margin     NUMERIC(20,8)  ;  -- 维持保证金
ALTER TABLE positions ADD COLUMN margin_balance   NUMERIC(20,8) ;  -- 逐仓保证金;全仓留账时记账户级

COMMENT ON COLUMN positions.leverage IS '杠杆倍数(PERP);SPOT NULL';
COMMENT ON COLUMN positions.margin_mode IS '保证金模式 ISOLATED/CROSS(PERP);SPOT NULL';
COMMENT ON COLUMN positions.position_side IS '持仓方向 LONG/SHORT(PERP 双向);SPOT NULL(side=long 字符串兼容)';
COMMENT ON COLUMN positions.liquidation_price IS '强平价(逐仓简化公式)';
COMMENT ON COLUMN positions.mark_price IS '标记价(强平判定用,ticker.last)';
COMMENT ON COLUMN positions.maint_margin IS '维持保证金';
COMMENT ON COLUMN positions.margin_balance IS '保证金余额(逐仓=该仓;全仓留账=账户级)';

-- orders 加合约列
ALTER TABLE orders ADD COLUMN leverage          INT            ;
ALTER TABLE orders ADD COLUMN margin_mode      VARCHAR(8)     ;
ALTER TABLE orders ADD COLUMN position_effect  VARCHAR(16)   ;  -- OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT,SPOT NULL

COMMENT ON COLUMN orders.leverage IS '杠杆倍数(PERP);SPOT NULL';
COMMENT ON COLUMN orders.margin_mode IS '保证金模式 ISOLATED/CROSS(PERP);SPOT NULL';
COMMENT ON COLUMN orders.position_effect IS '合约方向(OKX 四向)OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT;SPOT NULL';

-- positions 唯一索引扩合约维度:允许同账户同 symbol 的 SPOT + PERP 各自持仓,
-- PERP 同 symbol 多空(LONG/SHORT)双向持仓。
-- SPOT position_side NULL → COALESCE 'LONG'(现货只多);margin_mode NULL → 'SPOT'。
DROP INDEX IF EXISTS uk_positions_acct_sym;
CREATE UNIQUE INDEX uk_positions_acct_sym_mt ON positions (
    account_id, symbol,
    COALESCE(position_side, 'LONG'),
    COALESCE(margin_mode, 'SPOT')
);
