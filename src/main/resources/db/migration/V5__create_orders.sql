CREATE TABLE orders (
    id                  BIGSERIAL       PRIMARY KEY,
    account_id          BIGINT          NOT NULL,
    client_order_id     VARCHAR(64),
    exchange_order_id   VARCHAR(64),
    symbol              VARCHAR(32)     NOT NULL,
    side                VARCHAR(8)      NOT NULL,
    order_type          VARCHAR(32)     NOT NULL,
    amount              NUMERIC(20, 8)  NOT NULL,
    price               NUMERIC(20, 8),
    stop_price          NUMERIC(20, 8),
    time_in_force       VARCHAR(8)      NOT NULL DEFAULT 'GTC',
    expire_at           TIMESTAMPTZ,
    status              VARCHAR(24)     NOT NULL,
    filled_qty          NUMERIC(20, 8)  NOT NULL DEFAULT 0,
    filled_avg_price    NUMERIC(20, 8),
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_orders_client_oid ON orders (account_id, client_order_id) WHERE client_order_id IS NOT NULL;
CREATE UNIQUE INDEX uk_orders_exchange_oid ON orders (account_id, exchange_order_id) WHERE exchange_order_id IS NOT NULL;
CREATE INDEX idx_orders_acct_status ON orders (account_id, status, created_at);
CREATE INDEX idx_orders_expire ON orders (status, expire_at);

COMMENT ON TABLE orders IS '订单主表';
