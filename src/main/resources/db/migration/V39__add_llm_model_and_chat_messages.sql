-- A 层:llm_api_keys 加默认 model 列(OPENAI_COMPATIBLE 必填,OPENAI/ANTHROPIC 可选)
ALTER TABLE llm_api_keys ADD COLUMN model VARCHAR(100);
COMMENT ON COLUMN llm_api_keys.model IS '默认模型名(OPENAI_COMPATIBLE 必填;OPENAI/ANTHROPIC 可选,留空用 gpt-4o/claude-sonnet-4)';

-- C 层:AI 会话消息表(按策略组织,每策略一会话)
CREATE TABLE ai_chat_messages (
    id          BIGSERIAL       NOT NULL,
    user_id     BIGINT          NOT NULL,
    strategy_id BIGINT          NOT NULL,
    role        VARCHAR(10)     NOT NULL,
    content     TEXT            NOT NULL,
    model       VARCHAR(100),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_chat_messages PRIMARY KEY (id),
    CONSTRAINT fk_ai_chat_messages_strategy FOREIGN KEY (strategy_id) REFERENCES strategies(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_chat_messages_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_ai_chat_messages_strategy_created ON ai_chat_messages (strategy_id, created_at);
CREATE INDEX idx_ai_chat_messages_user ON ai_chat_messages (user_id);
COMMENT ON TABLE ai_chat_messages IS 'AI 会话消息(strategy 维度,每策略一个会话)';
COMMENT ON COLUMN ai_chat_messages.strategy_id IS '所属策略ID(strategy 删除级联清)';
COMMENT ON COLUMN ai_chat_messages.role IS '消息角色:user/ai';
COMMENT ON COLUMN ai_chat_messages.model IS 'AI 消息溯源:用的哪个 model(可空)';
