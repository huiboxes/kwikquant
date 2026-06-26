CREATE TABLE exchange_accounts (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    exchange        VARCHAR(32)  NOT NULL,
    label           VARCHAR(128) NOT NULL,
    api_key         VARCHAR(256) NOT NULL,
    api_secret      BYTEA        NOT NULL,
    passphrase      BYTEA,
    nonce           BYTEA        NOT NULL,
    passphrase_nonce BYTEA,
    key_version     INT          NOT NULL,
    paper_trading   BOOLEAN      NOT NULL DEFAULT TRUE,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_exchange_accounts_user_id ON exchange_accounts (user_id);
