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

    /**
     * 深度防御：WHERE 层再验 user_id，避免调用方漏做 getOwned 时越权更新。
     * {@code ExchangeAccount} 实体已含 userId 字段，MyBatis 自动取 #{userId}。
     */
    @Update(
            """
            UPDATE exchange_accounts
            SET label = #{label}, api_key = #{apiKey}, api_secret = #{apiSecret},
                passphrase = #{passphrase}, nonce = #{nonce}, passphrase_nonce = #{passphraseNonce},
                key_version = #{keyVersion}, paper_trading = #{paperTrading}, status = #{status}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(ExchangeAccount account);

    /** 深度防御：DELETE 层再验 user_id，避免调用方漏做 getOwned 时越权删除。 */
    @Delete("DELETE FROM exchange_accounts WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    @Select(
            """
            SELECT id, user_id, exchange, label, api_key, api_secret, passphrase,
                   nonce, passphrase_nonce, key_version, paper_trading, status, created_at, updated_at
            FROM exchange_accounts
            """)
    List<ExchangeAccount> findAll();
}
