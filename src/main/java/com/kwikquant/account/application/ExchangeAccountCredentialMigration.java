package com.kwikquant.account.application;

import com.kwikquant.account.application.KeyManagementService.EncryptedValue;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper.LegacyCredentials;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper.MigrationUpdate;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Migrates legacy plaintext API keys before the application starts accepting traffic. */
@Service
public class ExchangeAccountCredentialMigration {

    private final ExchangeAccountMapper mapper;
    private final KeyManagementService keyService;

    public ExchangeAccountCredentialMigration(ExchangeAccountMapper mapper, KeyManagementService keyService) {
        this.mapper = mapper;
        this.keyService = keyService;
    }

    @Transactional
    public void migrateOrFail() {
        for (LegacyCredentials row : mapper.findRowsRequiringCredentialMigration()) {
            migrateRow(row);
        }
    }

    private void migrateRow(LegacyCredentials row) {
        if (hasExpandedCredentials(row)) {
            clearLegacyColumns(row.id());
            return;
        }
        // 模拟盘账户本无真实凭据:旧代码仍会写入 key_version=0 占位标量(非加密材料),
        // 判定"无凭据"不能要求 keyVersion 为 null,否则遗留模拟盘行(key_version=0 + 其余列 NULL)
        // 会误落完整性检查阻断启动。clearLegacyColumns 会把 key_version 一并置 NULL。
        if (row.paperTrading() && noLegacyCredentialMaterial(row)) {
            clearLegacyColumns(row.id());
            return;
        }
        if (row.apiKey() == null || row.apiSecret() == null || row.nonce() == null || row.keyVersion() == null) {
            throw migrationFailure(row.id(), "legacy credential envelope is incomplete", null);
        }

        byte[] apiKeyPlain = row.apiKey().getBytes(StandardCharsets.UTF_8);
        byte[] secretPlain = null;
        byte[] passphrasePlain = null;
        try {
            secretPlain = keyService.decryptLegacy(row.apiSecret(), row.nonce(), row.keyVersion());
            if (row.passphrase() != null) {
                byte[] nonce = row.passphraseNonce() == null ? row.nonce() : row.passphraseNonce();
                passphrasePlain = keyService.decryptLegacy(row.passphrase(), nonce, row.keyVersion());
            }
            EncryptedValue apiKey = keyService.encryptCredential(apiKeyPlain);
            EncryptedValue secret = keyService.encryptCredential(secretPlain);
            EncryptedValue passphrase = passphrasePlain == null ? null : keyService.encryptCredential(passphrasePlain);
            int updated = mapper.migrateLegacyCredentials(new MigrationUpdate(
                    row.id(),
                    apiKey.ciphertext(),
                    apiKey.nonce(),
                    apiKey.keyVersion(),
                    secret.ciphertext(),
                    secret.nonce(),
                    secret.keyVersion(),
                    passphrase == null ? null : passphrase.ciphertext(),
                    passphrase == null ? null : passphrase.nonce(),
                    passphrase == null ? null : passphrase.keyVersion()));
            if (updated != 1) {
                throw migrationFailure(row.id(), "row changed concurrently", null);
            }
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Cannot safely migrate exchange account")) {
                throw e;
            }
            throw migrationFailure(row.id(), "credential decryption or encryption failed", e);
        } finally {
            Arrays.fill(apiKeyPlain, (byte) 0);
            if (secretPlain != null) Arrays.fill(secretPlain, (byte) 0);
            if (passphrasePlain != null) Arrays.fill(passphrasePlain, (byte) 0);
        }
    }

    private void clearLegacyColumns(long id) {
        int updated = mapper.clearLegacyCredentials(id);
        if (updated != 1) {
            throw migrationFailure(id, "row changed concurrently", null);
        }
    }

    private static boolean hasExpandedCredentials(LegacyCredentials row) {
        return row.apiKeyCiphertext() != null
                && row.apiKeyNonce() != null
                && row.apiKeyKeyVersion() != null
                && row.apiSecretCiphertext() != null
                && row.apiSecretNonce() != null
                && row.apiSecretKeyVersion() != null;
    }

    /** 遗留行是否不含任何加密材料。key_version 只是残留标量,不是凭据本体,不参与判定。 */
    private static boolean noLegacyCredentialMaterial(LegacyCredentials row) {
        return row.apiKey() == null
                && row.apiSecret() == null
                && row.passphrase() == null
                && row.nonce() == null
                && row.passphraseNonce() == null;
    }

    private static IllegalStateException migrationFailure(long id, String reason, Throwable cause) {
        return new IllegalStateException(
                "Cannot safely migrate exchange account " + id + ": " + reason
                        + ". Restore the configured ENCRYPTION_KEY/key version or replace this account's credentials, then restart.",
                cause);
    }
}
