-- V43: 资金费率结算落账表 —— 实盘 PERP 资金费率同步
-- OKX /api/v5/account/bills type=8 资金费率账单,8h 结算一次,本地落账供前端"累计资金费"展示 + 审计。
-- 不复用 fills 表:fills 语义是"成交",资金费率不是成交,混入语义不清且查询不便(前端"累计资金费"要走 fills 全表 SUM)。

CREATE TABLE funding_settlements (
    id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id BIGINT NOT NULL,
    position_id BIGINT,                       -- 可空(平仓后资金费率仍可能结算,待 spike 确认)
    symbol VARCHAR(32) NOT NULL,              -- CCXT 规范 BTC/USDT
    funding_rate NUMERIC(20, 10),              -- 费率(正=付,负=收);OKX bills 不返费率,留空,未来拉 /api/v5/public/funding-rate 反算或单拉
    qty_at_settle NUMERIC(20, 8) NOT NULL,    -- 结算时持仓量
    funding_amount NUMERIC(20, 8) NOT NULL,   -- 资金费金额(正=付,负=收)
    settle_time TIMESTAMP NOT NULL,           -- OKX 结算时刻
    bill_id VARCHAR(64),                      -- OKX billId 幂等键
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, bill_id)              -- 幂等(同一 bill 不重复落账)
);

CREATE INDEX idx_funding_settlements_account_symbol
    ON funding_settlements (account_id, symbol);

COMMENT ON TABLE funding_settlements IS
    '资金费率结算落账。OKX bills type=8 资金费率账单,8h 结算一次。不复用 fills(语义不清)';

COMMENT ON COLUMN funding_settlements.account_id IS '交易所账户 ID';
COMMENT ON COLUMN funding_settlements.position_id IS '持仓 ID(可空:平仓后资金费率仍可能结算,待 spike 确认是否真结算)';
COMMENT ON COLUMN funding_settlements.symbol IS '交易对 CCXT 规范 BTC/USDT';
COMMENT ON COLUMN funding_settlements.funding_rate IS '资金费率(正=付,负=收);OKX bills 不返费率,留空,未来拉 /api/v5/public/funding-rate';
COMMENT ON COLUMN funding_settlements.qty_at_settle IS '结算时持仓量';
COMMENT ON COLUMN funding_settlements.funding_amount IS '资金费金额(正=付,负=收)';
COMMENT ON COLUMN funding_settlements.settle_time IS 'OKX 结算时刻';
COMMENT ON COLUMN funding_settlements.bill_id IS 'OKX billId 幂等键';
COMMENT ON COLUMN funding_settlements.created_at IS '本地落账时刻';
