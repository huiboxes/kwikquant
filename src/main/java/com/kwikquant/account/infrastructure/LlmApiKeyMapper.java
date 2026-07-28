package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.LlmApiKey;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LlmApiKeyMapper {

    @Select(
            """
            SELECT id, user_id, label, provider, api_key, api_secret, nonce, key_version,
                   base_url, available_models, created_at, updated_at
            FROM llm_api_keys WHERE id = #{id}
            """)
    LlmApiKey findById(long id);

    @Select(
            """
            SELECT id, user_id, label, provider, api_key, api_secret, nonce, key_version,
                   base_url, available_models, created_at, updated_at
            FROM llm_api_keys WHERE user_id = #{userId} ORDER BY created_at DESC
            """)
    List<LlmApiKey> findByUserId(long userId);

    @Insert(
            """
            INSERT INTO llm_api_keys (user_id, label, provider, api_key, api_secret,
                                      nonce, key_version, base_url, available_models)
            VALUES (#{userId}, #{label}, #{provider}, #{apiKey}, #{apiSecret},
                    #{nonce}, #{keyVersion}, #{baseUrl}, #{availableModels})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmApiKey key);

    /**
     * 深度防御:mapper 层再做一次 user_id 校验,即使 caller 忘记先 {@code getOwned} 也不会越权删除。
     */
    @Delete("DELETE FROM llm_api_keys WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    /**
     * 更新密钥(全字段 SET 除 provider;provider 不可变,换 provider 请删除后新建)。
     * apiKey 留空时 Service 层把 entity 原值写回(幂等);非空时 entity 已被 Service 重新加密覆盖。
     * 深度防御:WHERE 含 user_id,返回 0 = 并发 owner 变更。
     */
    @Update(
            """
            UPDATE llm_api_keys
            SET label = #{label}, api_key = #{apiKey}, api_secret = #{apiSecret},
                nonce = #{nonce}, key_version = #{keyVersion}, base_url = #{baseUrl},
                available_models = #{availableModels}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int update(LlmApiKey key);
}
