package com.kwikquant.strategy.domain;

import java.time.Instant;

/**
 * AI 会话消息实体(tech-design §5 C 层)。
 *
 * <p>按策略组织(每策略一会话,YAGNI 不引入 session 表),与 {@link StrategyDefinition} 强关联:
 * strategy 删除时 FK ON DELETE CASCADE 级联清,防孤儿。user_id 冗余存便于 ownership 校验
 * (mapper SQL WHERE 含 user_id 深度防御,与 {@code LlmApiKeyMapper.deleteByIdAndUser} 一致)。
 *
 * <p>保存职责(tech-design §5.2):
 * <ul>
 *   <li>user 消息:后端 {@code AiChatController} 的 POST /ai/chat 进来时 blocking 保存
 *       (controller 层调 {@code AiChatMessageService.saveMessage},role="user")</li>
 *   <li>AI 回复:前端 SSE onClose 时 POST /strategies/{id}/ai/messages 保存
 *       (role="ai",content=完整 streamText,model=本次用的 model)</li>
 *   <li>onError 不存 AI(回复不完整不存)</li>
 * </ul>
 *
 * <p>保留传统 getter/setter(与项目现有实体风格一致,无 Lombok,参照 {@code LlmApiKey})。
 */
public class AiChatMessage {

    private Long id;
    private long userId;
    private long strategyId;
    private String role;
    private String content;
    private String model;
    private Instant createdAt;

    public AiChatMessage() {}

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

    public long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(long strategyId) {
        this.strategyId = strategyId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /** AI 消息溯源:用的哪个 model(可空,user 消息恒为 null)。 */
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
