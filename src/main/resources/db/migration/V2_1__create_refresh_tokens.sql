CREATE TABLE refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    jti        VARCHAR(64)  NOT NULL,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_refresh_tokens_jti UNIQUE (jti)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
