package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.domain.AiUsageLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * AI 调用 usage 计费日志 mapper。仅 {@code insert}(usage 是 append-only 计费流水,无查询/更新场景)。
 *
 * <p>依赖 {@code map-underscore-to-camel-case: true} 自动 snake_case→camelCase 映射
 * (与 {@code AiChatMessageMapper} 一致,不用 @Results)。{@code @Mapper} 注解让 MyBatis Spring Boot
 * 自动发现(项目无 @MapperScan,靠 @Mapper 注解扫描,参照 {@code AiChatMessageMapper})。
 */
@Mapper
public interface AiUsageLogMapper {

    @Insert(
            """
            INSERT INTO ai_usage_log (user_id, key_id, model, prompt_tokens, completion_tokens, source)
            VALUES (#{userId}, #{keyId}, #{model}, #{promptTokens}, #{completionTokens}, #{source})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AiUsageLog log);
}
