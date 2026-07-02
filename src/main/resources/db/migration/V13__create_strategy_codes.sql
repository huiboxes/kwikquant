CREATE TABLE strategy_codes (
    id              BIGSERIAL       NOT NULL,
    strategy_id     BIGINT          NOT NULL,
    version_number  INTEGER         NOT NULL,
    source_code     TEXT            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    language        VARCHAR(20)     NOT NULL DEFAULT 'python',
    changelog       TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_strategy_codes PRIMARY KEY (id),
    CONSTRAINT fk_strategy_codes_strategy FOREIGN KEY (strategy_id) REFERENCES strategies(id)
);

CREATE INDEX idx_strategy_codes_strategy_id ON strategy_codes (strategy_id);
CREATE UNIQUE INDEX uk_strategy_codes_strategy_version ON strategy_codes (strategy_id, version_number);
CREATE INDEX idx_strategy_codes_strategy_status ON strategy_codes (strategy_id, status);

COMMENT ON TABLE strategy_codes IS '策略代码版本表';
COMMENT ON COLUMN strategy_codes.id IS '代码版本ID';
COMMENT ON COLUMN strategy_codes.strategy_id IS '所属策略ID';
COMMENT ON COLUMN strategy_codes.version_number IS '版本号，同一策略内递增';
COMMENT ON COLUMN strategy_codes.source_code IS '策略源代码';
COMMENT ON COLUMN strategy_codes.status IS '版本状态: DRAFT/PUBLISHED/ARCHIVED';
COMMENT ON COLUMN strategy_codes.language IS '编程语言: python';
COMMENT ON COLUMN strategy_codes.changelog IS '版本变更说明';
COMMENT ON COLUMN strategy_codes.created_at IS '创建时间';
COMMENT ON COLUMN strategy_codes.updated_at IS '更新时间';
