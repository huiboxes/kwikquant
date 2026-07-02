CREATE TABLE backtest_reports (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    params          TEXT,
    symbol          VARCHAR(32)   NOT NULL,
    timeframe       VARCHAR(8)    NOT NULL,
    period_start    TIMESTAMPTZ   NOT NULL,
    period_end      TIMESTAMPTZ   NOT NULL,
    equity_curve    JSONB,
    total_return    NUMERIC(20,8),
    sharpe_ratio    NUMERIC(20,8),
    max_drawdown    NUMERIC(20,8),
    win_rate        NUMERIC(20,8),
    profit_factor   NUMERIC(20,8),
    total_trades    INTEGER       NOT NULL DEFAULT 0,
    avg_trade_duration_seconds BIGINT NOT NULL DEFAULT 0,
    source          VARCHAR(16)   NOT NULL DEFAULT 'PLATFORM',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_reports_user_id     ON backtest_reports (user_id, created_at DESC);
CREATE INDEX idx_reports_user_symbol ON backtest_reports (user_id, symbol);
