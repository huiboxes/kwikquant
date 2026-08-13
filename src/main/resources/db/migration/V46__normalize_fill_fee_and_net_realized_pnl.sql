-- fills.fee internal invariant: positive = cost, negative = rebate.
-- OKX REST historically persisted account-balance deltas (charged fee negative, rebate positive).
-- Only live OKX rows are flipped: paper OKX fills were generated internally with cost-positive fees.
UPDATE fills f
SET fee = -f.fee,
    updated_at = now()
FROM exchange_accounts a
WHERE a.id = f.account_id
  AND a.exchange = 'OKX'
  AND a.paper_trading = FALSE;

-- V42 stored gross directional PnL. Convert every fill to net PnL exactly once after fee normalization.
-- Legacy rows whose directional PnL was unavailable remain fee-only rather than being inferred from cashflow.
UPDATE fills
SET realized_pnl_delta = realized_pnl_delta - fee,
    updated_at = now();

COMMENT ON COLUMN fills.fee IS
    '有符号费用成本:普通手续费为正,返佣为负;交易所原始符号须在 adapter 边界转换';

COMMENT ON COLUMN fills.realized_pnl_delta IS
    '本笔成交净已实现损益:方向性平仓 PnL - 有符号费用成本;开仓通常为 -fee';
