CREATE TABLE backtest_tasks (
    id                  BIGSERIAL       NOT NULL,
    strategy_id         BIGINT          NOT NULL,
    user_id             BIGINT          NOT NULL,
    strategy_code_id    BIGINT          NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    symbol              VARCHAR(20)     NOT NULL,
    exchange            VARCHAR(20)     NOT NULL,
    interval_value      VARCHAR(10)     NOT NULL DEFAULT '1h',
    start_time          TIMESTAMPTZ     NOT NULL,
    end_time            TIMESTAMPTZ     NOT NULL,
    parameters          JSONB           NOT NULL DEFAULT '{}',
    result              JSONB,
    error_message       TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_backtest_tasks PRIMARY KEY (id),
    CONSTRAINT fk_backtest_tasks_strategy FOREIGN KEY (strategy_id) REFERENCES strategies(id),
    CONSTRAINT fk_backtest_tasks_code FOREIGN KEY (strategy_code_id) REFERENCES strategy_codes(id)
);

CREATE INDEX idx_backtest_tasks_strategy_id ON backtest_tasks (strategy_id);
CREATE INDEX idx_backtest_tasks_user_id ON backtest_tasks (user_id);
CREATE INDEX idx_backtest_tasks_status ON backtest_tasks (status);

COMMENT ON TABLE backtest_tasks IS '回测任务表';
COMMENT ON COLUMN backtest_tasks.id IS '任务ID';
COMMENT ON COLUMN backtest_tasks.strategy_id IS '所属策略ID';
COMMENT ON COLUMN backtest_tasks.user_id IS '提交用户ID';
COMMENT ON COLUMN backtest_tasks.strategy_code_id IS '使用的代码版本ID';
COMMENT ON COLUMN backtest_tasks.status IS '任务状态: PENDING/RUNNING/COMPLETED/FAILED';
COMMENT ON COLUMN backtest_tasks.symbol IS '回测交易对';
COMMENT ON COLUMN backtest_tasks.exchange IS '回测交易所';
COMMENT ON COLUMN backtest_tasks.interval_value IS 'K线周期';
COMMENT ON COLUMN backtest_tasks.start_time IS '回测开始时间';
COMMENT ON COLUMN backtest_tasks.end_time IS '回测结束时间';
COMMENT ON COLUMN backtest_tasks.parameters IS '回测参数 JSON';
COMMENT ON COLUMN backtest_tasks.result IS '回测结果 JSON (BacktestResult 序列化)';
COMMENT ON COLUMN backtest_tasks.error_message IS '失败原因';
COMMENT ON COLUMN backtest_tasks.created_at IS '创建时间';
COMMENT ON COLUMN backtest_tasks.updated_at IS '更新时间';
