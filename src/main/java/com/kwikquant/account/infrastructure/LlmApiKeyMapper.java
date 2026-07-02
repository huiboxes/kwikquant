package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.LlmApiKey;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LlmApiKeyMapper {

    @Select(
            """
            SELECT id, user_id, label, provider, api_key, api_secret, nonce, key_version,
                   base_url, created_at, updated_at
            FROM llm_api_keys WHERE id = #{id}
            """)
    LlmApiKey findById(long id);

    @Select(
            """
            SELECT id, user_id, label, provider, api_key, api_secret, nonce, key_version,
                   base_url, created_at, updated_at
            FROM llm_api_keys WHERE user_id = #{userId} ORDER BY created_at DESC
            """)
    List<LlmApiKey> findByUserId(long userId);

    @Insert(
            """
            INSERT INTO llm_api_keys (user_id, label, provider, api_key, api_secret,
                                      nonce, key_version, base_url)
            VALUES (#{userId}, #{label}, #{provider}, #{apiKey}, #{apiSecret},
                    #{nonce}, #{keyVersion}, #{baseUrl})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmApiKey key);

    /**
     * 深度防御：mapper 层再做一次 user_id 校验，即使 caller 忘记先 {@code getOwned} 也不会越权删除。
     */
    @Delete("DELETE FROM llm_api_keys WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);
}
