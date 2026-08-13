package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.ExchangeAccount;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExchangeAccountMapper {

    @Select(
            """
             SELECT id, user_id, exchange, label, api_key_ciphertext, api_key_nonce, api_key_key_version,
                    api_secret_ciphertext, api_secret_nonce, api_secret_key_version,
                    passphrase_ciphertext, passphrase_encryption_nonce, passphrase_key_version,
                    paper_trading, testnet, status, created_at, updated_at
            FROM exchange_accounts WHERE id = #{id}
            """)
    ExchangeAccount findById(long id);

    @Select(
            """
             SELECT id, user_id, exchange, label, api_key_ciphertext, api_key_nonce, api_key_key_version,
                    api_secret_ciphertext, api_secret_nonce, api_secret_key_version,
                    passphrase_ciphertext, passphrase_encryption_nonce, passphrase_key_version,
                    paper_trading, testnet, status, created_at, updated_at
            FROM exchange_accounts WHERE user_id = #{userId}
            """)
    List<ExchangeAccount> findByUserId(long userId);

    /**
     * Worker→Java POST /api/v1/orders 从 WorkerTokenFilter 注入的 userId+exchange
     * 推导 ExchangeAccount(避 trading→strategy 模块违规)。同一 (user, exchange) 若有多个账户返回
     * 第一条(通常 1 用户 x 1 exchange 只维护 1 账户)。
     */
    @Select(
            """
             SELECT id, user_id, exchange, label, api_key_ciphertext, api_key_nonce, api_key_key_version,
                    api_secret_ciphertext, api_secret_nonce, api_secret_key_version,
                    passphrase_ciphertext, passphrase_encryption_nonce, passphrase_key_version,
                    paper_trading, testnet, status, created_at, updated_at
            FROM exchange_accounts
            WHERE user_id = #{userId} AND exchange = #{exchange}
            ORDER BY id ASC LIMIT 1
            """)
    ExchangeAccount findByUserAndExchange(@Param("userId") long userId, @Param("exchange") String exchange);

    @Insert(
            """
             INSERT INTO exchange_accounts (user_id, exchange, label,
                    api_key_ciphertext, api_key_nonce, api_key_key_version,
                    api_secret_ciphertext, api_secret_nonce, api_secret_key_version,
                    passphrase_ciphertext, passphrase_encryption_nonce, passphrase_key_version,
                    paper_trading, testnet, status)
             VALUES (#{userId}, #{exchange}, #{label},
                    #{apiKeyCiphertext}, #{apiKeyNonce}, #{apiKeyKeyVersion},
                    #{apiSecretCiphertext}, #{apiSecretNonce}, #{apiSecretKeyVersion},
                    #{passphraseCiphertext}, #{passphraseEncryptionNonce}, #{passphraseKeyVersion},
                    #{paperTrading}, #{testnet}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ExchangeAccount account);

    /**
     * 深度防御：WHERE 层再验 user_id，避免调用方漏做 getOwned 时越权更新。
     * {@code ExchangeAccount} 实体已含 userId 字段，MyBatis 自动取 #{userId}。
     */
    @Update(
            """
            UPDATE exchange_accounts
             SET label = #{label}, api_key_ciphertext = #{apiKeyCiphertext},
                 api_key_nonce = #{apiKeyNonce}, api_key_key_version = #{apiKeyKeyVersion},
                 api_secret_ciphertext = #{apiSecretCiphertext}, api_secret_nonce = #{apiSecretNonce},
                 api_secret_key_version = #{apiSecretKeyVersion}, passphrase_ciphertext = #{passphraseCiphertext},
                 passphrase_encryption_nonce = #{passphraseEncryptionNonce}, passphrase_key_version = #{passphraseKeyVersion},
                 paper_trading = #{paperTrading}, testnet = #{testnet}, status = #{status}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(ExchangeAccount account);

    /** 深度防御：DELETE 层再验 user_id，避免调用方漏做 getOwned 时越权删除。 */
    @Delete("DELETE FROM exchange_accounts WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    @Select(
            """
             SELECT id, user_id, exchange, label, api_key_ciphertext, api_key_nonce, api_key_key_version,
                    api_secret_ciphertext, api_secret_nonce, api_secret_key_version,
                    passphrase_ciphertext, passphrase_encryption_nonce, passphrase_key_version,
                    paper_trading, testnet, status, created_at, updated_at
            FROM exchange_accounts
            """)
    List<ExchangeAccount> findAll();

    @Select(
            """
            SELECT id, paper_trading, api_key, api_secret, passphrase, nonce, passphrase_nonce, key_version,
                   api_key_ciphertext, api_key_nonce, api_key_key_version,
                   api_secret_ciphertext, api_secret_nonce, api_secret_key_version
            FROM exchange_accounts
            WHERE api_key IS NOT NULL OR api_secret IS NOT NULL OR passphrase IS NOT NULL
               OR nonce IS NOT NULL OR passphrase_nonce IS NOT NULL OR key_version IS NOT NULL
               OR (paper_trading = FALSE AND (api_key_ciphertext IS NULL OR api_key_nonce IS NULL
                   OR api_key_key_version IS NULL OR api_secret_ciphertext IS NULL
                   OR api_secret_nonce IS NULL OR api_secret_key_version IS NULL))
            ORDER BY id
            """)
    List<LegacyCredentials> findRowsRequiringCredentialMigration();

    @Update(
            """
            UPDATE exchange_accounts
            SET api_key_ciphertext = #{apiKeyCiphertext}, api_key_nonce = #{apiKeyNonce},
                api_key_key_version = #{apiKeyKeyVersion}, api_secret_ciphertext = #{apiSecretCiphertext},
                api_secret_nonce = #{apiSecretNonce}, api_secret_key_version = #{apiSecretKeyVersion},
                passphrase_ciphertext = #{passphraseCiphertext},
                passphrase_encryption_nonce = #{passphraseEncryptionNonce},
                passphrase_key_version = #{passphraseKeyVersion},
                api_key = NULL, api_secret = NULL, passphrase = NULL, nonce = NULL,
                passphrase_nonce = NULL, key_version = NULL, updated_at = now()
            WHERE id = #{id}
            """)
    int migrateLegacyCredentials(MigrationUpdate update);

    @Update(
            """
            UPDATE exchange_accounts
            SET api_key = NULL, api_secret = NULL, passphrase = NULL, nonce = NULL,
                passphrase_nonce = NULL, key_version = NULL, updated_at = now()
            WHERE id = #{id}
            """)
    int clearLegacyCredentials(long id);

    record LegacyCredentials(
            long id,
            boolean paperTrading,
            String apiKey,
            byte[] apiSecret,
            byte[] passphrase,
            byte[] nonce,
            byte[] passphraseNonce,
            Integer keyVersion,
            byte[] apiKeyCiphertext,
            byte[] apiKeyNonce,
            Integer apiKeyKeyVersion,
            byte[] apiSecretCiphertext,
            byte[] apiSecretNonce,
            Integer apiSecretKeyVersion) {
        @Override
        public String toString() {
            return "LegacyCredentials[id=" + id + ", redacted]";
        }
    }

    record MigrationUpdate(
            long id,
            byte[] apiKeyCiphertext,
            byte[] apiKeyNonce,
            Integer apiKeyKeyVersion,
            byte[] apiSecretCiphertext,
            byte[] apiSecretNonce,
            Integer apiSecretKeyVersion,
            byte[] passphraseCiphertext,
            byte[] passphraseEncryptionNonce,
            Integer passphraseKeyVersion) {
        @Override
        public String toString() {
            return "MigrationUpdate[id=" + id + ", redacted]";
        }
    }
}
