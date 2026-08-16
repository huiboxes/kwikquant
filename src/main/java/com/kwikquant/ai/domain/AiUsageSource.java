package com.kwikquant.ai.domain;

/**
 * AI 调用 usage 计费来源。区分三类 LLM 调用,落库时取 {@link #name()} 小写存为
 * {@code ai_usage_log.source}(chat/summary/test),由 {@code AiUsageLogService} 转换。
 *
 * <p>纯枚举,无 Spring 依赖(放 domain 符合 ArchUnit:domain 不依赖 Spring)。
 */
public enum AiUsageSource {
    /** 用户对话(POST /ai/chat 主流)。 */
    CHAT,
    /** 上下文摘要(ContextWindowManager 压缩时调 LLM 摘要历史)。 */
    SUMMARY,
    /** 连通性测试(POST /ai/keys/{id}/test,messages=[hi] max_tokens=1)。 */
    TEST
}
