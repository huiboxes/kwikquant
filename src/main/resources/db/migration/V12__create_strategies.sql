CREATE TABLE strategies (
    id              BIGSERIAL       NOT NULL,
    user_id         BIGINT          NOT NULL,
    name            VARCHAR(200)    NOT NULL,
    description     VARCHAR(2000),
    symbol          VARCHAR(20)     NOT NULL,
    exchange        VARCHAR(20)     NOT NULL,
    market_type     VARCHAR(10)     NOT NULL DEFAULT 'SPOT',
    interval_value  VARCHAR(10)     NOT NULL DEFAULT '1h',
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    parameters      JSONB           NOT NULL DEFAULT '{}',
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_strategies PRIMARY KEY (id)
);

CREATE INDEX idx_strategies_user_id ON strategies (user_id);
CREATE INDEX idx_strategies_user_status ON strategies (user_id, status) WHERE deleted = FALSE;

COMMENT ON TABLE strategies IS '策略定义表';
COMMENT ON COLUMN strategies.id IS '策略ID';
COMMENT ON COLUMN strategies.user_id IS '所属用户ID';
COMMENT ON COLUMN strategies.name IS '策略名称';
COMMENT ON COLUMN strategies.description IS '策略描述';
COMMENT ON COLUMN strategies.symbol IS '交易对，CCXT 格式如 BTC/USDT';
COMMENT ON COLUMN strategies.exchange IS '交易所枚举值';
COMMENT ON COLUMN strategies.market_type IS '市场类型: SPOT / PERP';
COMMENT ON COLUMN strategies.interval_value IS 'K线周期，CCXT格式如 1m / 1h / 1d';
COMMENT ON COLUMN strategies.status IS '策略状态: DRAFT/READY/RUNNING/PAUSED/STOPPED/ERROR';
COMMENT ON COLUMN strategies.parameters IS '策略自定义参数 JSON';
COMMENT ON COLUMN strategies.deleted IS '软删除标记';
COMMENT ON COLUMN strategies.created_at IS '创建时间';
COMMENT ON COLUMN strategies.updated_at IS '更新时间';
