-- Expand only. Existing api_key values are plaintext and MUST NOT be copied by SQL.
-- ExchangeAccountCredentialMigration encrypts and clears legacy columns before the
-- application context becomes ready. A failed migration aborts startup.
ALTER TABLE exchange_accounts
    ADD COLUMN api_key_ciphertext BYTEA,
    ADD COLUMN api_key_nonce BYTEA,
    ADD COLUMN api_key_key_version INT,
    ADD COLUMN api_secret_ciphertext BYTEA,
    ADD COLUMN api_secret_nonce BYTEA,
    ADD COLUMN api_secret_key_version INT,
    ADD COLUMN passphrase_ciphertext BYTEA,
    ADD COLUMN passphrase_encryption_nonce BYTEA,
    ADD COLUMN passphrase_key_version INT;

ALTER TABLE exchange_accounts DROP CONSTRAINT chk_exchange_accounts_live_requires_key;

-- Permit either a complete legacy envelope during the controlled migration or the
-- complete expanded envelopes used by the new application. Partial envelopes fail closed.
ALTER TABLE exchange_accounts ADD CONSTRAINT chk_exchange_accounts_live_credentials
    CHECK (
        paper_trading = TRUE
        OR (
            api_key_ciphertext IS NOT NULL AND api_key_nonce IS NOT NULL AND api_key_key_version IS NOT NULL
            AND api_secret_ciphertext IS NOT NULL AND api_secret_nonce IS NOT NULL AND api_secret_key_version IS NOT NULL
        )
        OR (api_key IS NOT NULL AND api_secret IS NOT NULL AND nonce IS NOT NULL AND key_version IS NOT NULL)
    );

ALTER TABLE exchange_accounts ADD CONSTRAINT chk_exchange_accounts_passphrase_envelope
    CHECK (
        (passphrase_ciphertext IS NULL AND passphrase_encryption_nonce IS NULL AND passphrase_key_version IS NULL)
        OR (passphrase_ciphertext IS NOT NULL AND passphrase_encryption_nonce IS NOT NULL AND passphrase_key_version IS NOT NULL)
    );
