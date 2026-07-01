CREATE TABLE positions (
    id                  BIGSERIAL       PRIMARY KEY,
    account_id          BIGINT          NOT NULL,
    symbol              VARCHAR(32)     NOT NULL,
    side                VARCHAR(8)      NOT NULL DEFAULT 'flat',
    qty                 NUMERIC(20, 8)  NOT NULL DEFAULT 0,
    avg_entry_price     NUMERIC(20, 8),
    realized_pnl        NUMERIC(20, 8)  NOT NULL DEFAULT 0,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_positions_acct_sym ON positions (account_id, symbol);

COMMENT ON TABLE positions IS '持仓表';
