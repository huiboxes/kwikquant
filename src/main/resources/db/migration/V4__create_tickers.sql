CREATE TABLE tickers (
    exchange      VARCHAR(20)    NOT NULL,
    market_type   VARCHAR(10)    NOT NULL,
    symbol        VARCHAR(30)    NOT NULL,
    last_price    NUMERIC,
    bid           NUMERIC,
    ask           NUMERIC,
    high          NUMERIC,
    low           NUMERIC,
    open_price    NUMERIC,
    base_volume   NUMERIC,
    quote_volume  NUMERIC,
    change        NUMERIC,
    percentage    NUMERIC,
    event_time    TIMESTAMPTZ    NOT NULL,
    received_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, market_type, symbol)
);
