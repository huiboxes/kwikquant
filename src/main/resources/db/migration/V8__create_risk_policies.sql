CREATE TABLE risk_policies (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      BIGINT          NOT NULL,
    rule_type       VARCHAR(32)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    params          JSONB           NOT NULL DEFAULT '{}',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_policies_acct ON risk_policies (account_id);
CREATE UNIQUE INDEX uk_risk_policies_acct_type ON risk_policies (account_id, rule_type);
