CREATE TABLE risk_decisions (
    id              BIGSERIAL       PRIMARY KEY,
    request_id      VARCHAR(64)     NOT NULL,
    order_id        BIGINT          NOT NULL,
    account_id      BIGINT          NOT NULL,
    verdict         VARCHAR(16)     NOT NULL,
    rule_results    JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_risk_decisions_req ON risk_decisions (request_id);
CREATE INDEX idx_risk_decisions_order ON risk_decisions (order_id);
CREATE INDEX idx_risk_decisions_acct ON risk_decisions (account_id, created_at);
