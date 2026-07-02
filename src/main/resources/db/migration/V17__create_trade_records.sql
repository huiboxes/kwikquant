CREATE TABLE trade_records (
    id         BIGSERIAL PRIMARY KEY,
    report_id  BIGINT        NOT NULL REFERENCES backtest_reports(id) ON DELETE CASCADE,
    time       TIMESTAMPTZ   NOT NULL,
    side       VARCHAR(8)    NOT NULL,
    price      NUMERIC(20,8) NOT NULL,
    amount     NUMERIC(20,8) NOT NULL,
    fee        NUMERIC(20,8) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_records_report ON trade_records (report_id, time);
