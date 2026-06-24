CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT NOT NULL DEFAULT 0,
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id   VARCHAR(255) NOT NULL DEFAULT 'unknown',
    trace_id    VARCHAR(64),
    status      VARCHAR(10) NOT NULL,
    error       TEXT,
    metadata    JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_action ON audit_logs (action);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);

-- Append-only protection: prevent UPDATE and DELETE on audit_logs
CREATE RULE audit_no_update AS ON UPDATE TO audit_logs DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_logs DO INSTEAD NOTHING;
