CREATE TABLE encryption_keys (
    id            BIGSERIAL    PRIMARY KEY,
    key_version   INT          NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_encryption_keys_version UNIQUE (key_version)
);
