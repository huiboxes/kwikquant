CREATE TABLE fills (
    id                  BIGSERIAL       PRIMARY KEY,
    order_id            BIGINT          NOT NULL,
    account_id          BIGINT          NOT NULL,
    symbol              VARCHAR(32)     NOT NULL,
    side                VARCHAR(8)      NOT NULL,
    price               NUMERIC(20, 8)  NOT NULL,
    qty                 NUMERIC(20, 8)  NOT NULL,
    fee                 NUMERIC(20, 8)  NOT NULL DEFAULT 0,
    fee_currency        VARCHAR(16),
    liquidity           VARCHAR(8),
    external_fill_id    VARCHAR(64),
    filled_at           TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_fills_ext_fill_id ON fills (account_id, external_fill_id) WHERE external_fill_id IS NOT NULL;
CREATE INDEX idx_fills_order ON fills (order_id, filled_at);
CREATE INDEX idx_fills_acct_time ON fills (account_id, filled_at);

COMMENT ON TABLE fills IS '成交记录表';
