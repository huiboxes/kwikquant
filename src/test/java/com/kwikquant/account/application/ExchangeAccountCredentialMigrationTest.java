package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper.LegacyCredentials;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper.MigrationUpdate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExchangeAccountCredentialMigrationTest {

    private ExchangeAccountMapper mapper;
    private KeyManagementService keyService;
    private ExchangeAccountCredentialMigration migration;

    @BeforeEach
    void setUp() {
        mapper = mock(ExchangeAccountMapper.class);
        keyService = mock(KeyManagementService.class);
        migration = new ExchangeAccountCredentialMigration(mapper, keyService);
    }

    @Test
    void migratesPlaintextApiKeyAndLegacyEncryptedFieldsThenClearsLegacyColumns() {
        LegacyCredentials row = legacyRow();
        when(mapper.findRowsRequiringCredentialMigration()).thenReturn(List.of(row));
        when(keyService.decryptLegacy(row.apiSecret(), row.nonce(), 1)).thenReturn(bytes("secret"));
        when(keyService.decryptLegacy(row.passphrase(), row.passphraseNonce(), 1))
                .thenReturn(bytes("pass"));
        when(keyService.encryptCredential(any()))
                .thenReturn(encrypted(2, (byte) 1), encrypted(2, (byte) 2), encrypted(2, (byte) 3));
        when(mapper.migrateLegacyCredentials(any())).thenReturn(1);

        migration.migrateOrFail();

        ArgumentCaptor<MigrationUpdate> update = ArgumentCaptor.forClass(MigrationUpdate.class);
        verify(mapper).migrateLegacyCredentials(update.capture());
        assertThat(update.getValue().apiKeyCiphertext()).isNotEqualTo(bytes("plain-api-key"));
        assertThat(update.getValue().apiKeyKeyVersion()).isEqualTo(2);
        assertThat(update.getValue().apiSecretKeyVersion()).isEqualTo(2);
        assertThat(update.getValue().passphraseKeyVersion()).isEqualTo(2);
    }

    @Test
    void wrongLegacyKeyFailsClosedWithoutWriting() {
        LegacyCredentials row = legacyRow();
        when(mapper.findRowsRequiringCredentialMigration()).thenReturn(List.of(row));
        when(keyService.decryptLegacy(row.apiSecret(), row.nonce(), 1))
                .thenThrow(new IllegalStateException("AES-256-GCM decryption failed"));

        assertThatThrownBy(migration::migrateOrFail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot safely migrate exchange account 7")
                .hasMessageContaining("ENCRYPTION_KEY");
        verify(mapper, never()).migrateLegacyCredentials(any());
    }

    @Test
    void incompleteLegacyEnvelopeBlocksStartup() {
        LegacyCredentials row =
                new LegacyCredentials(8, false, "key", null, null, null, null, 1, null, null, null, null, null, null);
        when(mapper.findRowsRequiringCredentialMigration()).thenReturn(List.of(row));

        assertThatThrownBy(migration::migrateOrFail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
        verify(mapper, never()).migrateLegacyCredentials(any());
    }

    @Test
    void legacyPaperAccountWithKeyVersionZeroPlaceholder_clearsWithoutBlockingStartup() {
        // 回归:旧代码模拟盘账户写 key_version=0(占位标量)+ 其余凭据列 NULL。
        // 若早退分支误要求 keyVersion 为 null,此行会落完整性检查抛异常阻断启动。
        LegacyCredentials row =
                new LegacyCredentials(9, true, null, null, null, null, null, 0, null, null, null, null, null, null);
        when(mapper.findRowsRequiringCredentialMigration()).thenReturn(List.of(row));
        when(mapper.clearLegacyCredentials(9L)).thenReturn(1);

        migration.migrateOrFail();

        verify(mapper).clearLegacyCredentials(9L);
        verify(mapper, never()).migrateLegacyCredentials(any());
    }

    private static LegacyCredentials legacyRow() {
        return new LegacyCredentials(
                7,
                false,
                "plain-api-key",
                new byte[] {10},
                new byte[] {11},
                new byte[] {12},
                new byte[] {13},
                1,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static KeyManagementService.EncryptedValue encrypted(int version, byte marker) {
        return new KeyManagementService.EncryptedValue(new byte[] {marker}, new byte[12], version);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
