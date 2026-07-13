-- Add realized_pnl and equity columns to trade_records.
-- These fields allow the UI to display per-trade PnL and cumulative equity
-- without re-computing from the full trade sequence.
ALTER TABLE trade_records
    ADD COLUMN realized_pnl NUMERIC(20, 8),
    ADD COLUMN equity       NUMERIC(20, 8);
