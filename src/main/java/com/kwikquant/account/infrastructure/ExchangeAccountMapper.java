package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.ExchangeAccount;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExchangeAccountMapper {

    @Select(
            """
            SELECT id, user_id, exchange, label, api_key, api_secret, passphrase,
                   nonce, passphrase_nonce, key_version, paper_trading, status, created_at, updated_at
            FROM exchange_accounts WHERE id = #{id}
            """)
    ExchangeAccount findById(long id);

    @Select(
            """
            SELECT id, user_id, exchange, label, api_key, api_secret, passphrase,
                   nonce, passphrase_nonce, key_version, paper_trading, status, created_at, updated_at
            FROM exchange_accounts WHERE user_id = #{userId}
            """)
    List<ExchangeAccount> findByUserId(long userId);

    @Insert(
            """
            INSERT INTO exchange_accounts (user_id, exchange, label, api_key, api_secret,
                                           passphrase, nonce, passphrase_nonce, key_version, paper_trading, status)
            VALUES (#{userId}, #{exchange}, #{label}, #{apiKey}, #{apiSecret},
                    #{passphrase}, #{nonce}, #{passphraseNonce}, #{keyVersion}, #{paperTrading}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ExchangeAccount account);

    @Update(
            """
            UPDATE exchange_accounts
            SET label = #{label}, api_key = #{apiKey}, api_secret = #{apiSecret},
                passphrase = #{passphrase}, nonce = #{nonce}, passphrase_nonce = #{passphraseNonce},
                key_version = #{keyVersion}, paper_trading = #{paperTrading}, status = #{status}, updated_at = now()
            WHERE id = #{id}
            """)
    int update(ExchangeAccount account);

    @Delete("DELETE FROM exchange_accounts WHERE id = #{id}")
    int deleteById(long id);

    @Select(
            """
            SELECT id, user_id, exchange, label, api_key, api_secret, passphrase,
                   nonce, passphrase_nonce, key_version, paper_trading, status, created_at, updated_at
            FROM exchange_accounts
            """)
    List<ExchangeAccount> findAll();
}
