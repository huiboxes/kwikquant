package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.McpToken;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MCP PAT MyBatis 注解 mapper（参照 {@code LlmApiKeyMapper} 风格，XML-free）。
 *
 * <p>{@code created_at}/{@code updated_at} 由应用层 set 后传入 INSERT（见 {@code McpTokenServiceImpl}），
 * 与 V18 {@code DEFAULT now()} 兜底一致；update 语句显式带 {@code updated_at = now()} 维护「应用层维护」语义。
 */
@Mapper
public interface McpTokenMapper {

    @Insert(
            """
            INSERT INTO mcp_tokens (user_id, name, token_hash, salt, expires_at, created_at, updated_at)
            VALUES (#{userId}, #{name}, #{tokenHash}, #{salt}, #{expiresAt}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(McpToken token);

    @Select(
            """
            SELECT id, user_id, name, token_hash, salt, last_used_at, expires_at, revoked_at,
                   created_at, updated_at
            FROM mcp_tokens WHERE token_hash = #{tokenHash}
            """)
    McpToken findByTokenHash(String tokenHash);

    @Select(
            """
            SELECT id, user_id, name, token_hash, salt, last_used_at, expires_at, revoked_at,
                   created_at, updated_at
            FROM mcp_tokens WHERE user_id = #{userId} ORDER BY created_at DESC
            """)
    List<McpToken> findByUserId(long userId);

    /** 深度防御：WHERE 含 user_id 防越权吊销；{@code revoked_at IS NULL} 防重复吊销幂等。返回 0=不存在/已吊销/越权。 */
    @Update("UPDATE mcp_tokens SET revoked_at = now(), updated_at = now() "
            + "WHERE id = #{id} AND user_id = #{userId} AND revoked_at IS NULL")
    int updateRevokedAt(@Param("id") long id, @Param("userId") long userId);

    /** verify 热路径：更新 last_used_at。由 service 层 {@code @Transactional(REQUIRES_NEW)} 包独立事务 + try-catch swallow。 */
    @Update("UPDATE mcp_tokens SET last_used_at = now(), updated_at = now() WHERE id = #{id}")
    int updateLastUsedAt(long id);
}
