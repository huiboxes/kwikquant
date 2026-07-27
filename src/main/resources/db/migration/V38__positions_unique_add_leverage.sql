-- 方案 B:不同杠杆不同持仓(positions 唯一索引加 leverage)
-- 原 uk_positions_acct_sym_mt (V31 建 UNIQUE INDEX,account_id, symbol, position_side, margin_mode) 不含 leverage,
-- 导致不同杠杆聚合成 1 个 position,applyPerpDelta 用 position 首次 leverage 算保证金/强平价
-- (PositionService.java:259),1x 订单按 100x 算保证金(少算 99%),金额对不上。
-- 新索引加 COALESCE(leverage, 0):不同 leverage → 不同 position(各自保证金/强平价独立)。
-- SPOT leverage NULL → COALESCE 0,与 PERP leverage(1-125)不冲突(SPOT 不走 findByAccountSymbolPosition)。
-- 用 INDEX 不用 CONSTRAINT:COALESCE 表达式索引只有 INDEX 支持(CONSTRAINT UNIQUE 只支持纯列)。
DROP INDEX IF EXISTS uk_positions_acct_sym_mt;
CREATE UNIQUE INDEX uk_positions_acct_sym_mt_lev ON positions (
    account_id, symbol, COALESCE(position_side, 'LONG'), COALESCE(margin_mode, 'SPOT'), COALESCE(leverage, 0)
);
