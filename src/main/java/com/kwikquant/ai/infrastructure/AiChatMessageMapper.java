package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.domain.AiChatMessage;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 会话消息 mapper。
 *
 * <p>所有写/查 SQL 在 WHERE 含 user_id(深度防御,与 {@code LlmApiKeyMapper.deleteByIdAndUser}
 * 和 {@code StrategyMapper.softDelete} 一致):即使 caller 忘记先 {@code crudService.getOwned}
 * 也不会越权读/写他人策略的消息。service 层已做 ownership 校验,本层是兜底。
 *
 * <p>依赖 {@code map-underscore-to-camel-case: true} 自动 snake_case→camelCase 映射
 * (与 {@code LlmApiKeyMapper} 一致,不用 @Results)。
 */
@Mapper
public interface AiChatMessageMapper {

    @Insert(
            """
            INSERT INTO ai_chat_messages (user_id, strategy_id, role, content, model)
            VALUES (#{userId}, #{strategyId}, #{role}, #{content}, #{model})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AiChatMessage message);

    /**
     * 按策略查会话历史(最近 N 条,created_at 升序显示)。子查询 DESC LIMIT N 取最近 N 条
     * (防爆且不丢最新对话——朴素 ASC LIMIT 会返最早 N 条,超 200 条时丢最新对话),
     * 外层 ASC 升序显示(最早在前,最近在后,符合会话时间线)。
     */
    @Select(
            """
            SELECT id, user_id, strategy_id, role, content, model, created_at
            FROM (
                SELECT id, user_id, strategy_id, role, content, model, created_at
                FROM ai_chat_messages
                WHERE strategy_id = #{strategyId} AND user_id = #{userId}
                ORDER BY created_at DESC
                LIMIT #{limit}
            ) recent
            ORDER BY created_at ASC
            """)
    List<AiChatMessage> listByStrategy(
            @Param("strategyId") long strategyId, @Param("userId") long userId, @Param("limit") int limit);

    /**
     * 清空该策略的会话消息。返回删除行数(0=并发已清或非 owner,caller 可选审计)。
     */
    @Delete(
            """
            DELETE FROM ai_chat_messages
            WHERE strategy_id = #{strategyId} AND user_id = #{userId}
            """)
    int deleteByStrategy(@Param("strategyId") long strategyId, @Param("userId") long userId);
}
