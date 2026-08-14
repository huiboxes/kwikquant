package com.kwikquant.ai.domain;

import java.time.Instant;

/**
 * AI 调用 usage 计费日志实体。每次 LLM 调用(chat/summary/test)各记一笔,token 数从 provider SSE
 * usage 帧提取(OpenAI {@code stream_options.include_usage} 末帧 / Anthropic message_start +
 * message_delta 跨两帧)。
 *
 * <p>FK 级联:{@code user_id}→{@code users.id}、{@code key_id}→{@code llm_api_keys.id} 均
 * {@code ON DELETE CASCADE},用户注销/密钥删除时清理 usage 记录防孤儿。{@code source} 取
 * {@link AiUsageSource#name()} 小写({@code chat}/{@code summary}/{@code test}),由
 * {@code AiUsageLogService} 转换落库。
 *
 * <p>保留传统 getter/setter(与项目现有实体风格一致,无 Lombok,参照 {@code AiChatMessage}/{@code LlmApiKey})。
 */
public class AiUsageLog {

    private Long id;
    private long userId;
    private long keyId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private String source;
    private Instant createdAt;

    public AiUsageLog() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getKeyId() {
        return keyId;
    }

    public void setKeyId(long keyId) {
        this.keyId = keyId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
