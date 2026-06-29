CREATE TABLE klines (
    exchange      VARCHAR(20)    NOT NULL,
    market_type   VARCHAR(10)    NOT NULL,
    symbol        VARCHAR(30)    NOT NULL,
    interval      VARCHAR(5)     NOT NULL,
    open_time     TIMESTAMPTZ    NOT NULL,
    open          NUMERIC        NOT NULL,
    high          NUMERIC        NOT NULL,
    low           NUMERIC        NOT NULL,
    close         NUMERIC        NOT NULL,
    volume        NUMERIC        NOT NULL,
    PRIMARY KEY (exchange, market_type, symbol, interval, open_time)
);

CREATE INDEX idx_klines_lookup
    ON klines (exchange, market_type, symbol, interval, open_time DESC);
