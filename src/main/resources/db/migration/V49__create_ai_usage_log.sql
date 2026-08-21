-- AI 调用 usage 计费日志:chat(用户对话)/summary(上下文摘要)/test(连通性测试)三类调用各记一笔。
-- OpenAI 走 stream_options.include_usage 末帧 usage;Anthropic 取 message_start.usage.input_tokens + message_delta.usage.output_tokens。
-- FK 级联:用户注销/密钥删除时清理 usage 记录,防孤儿。
CREATE TABLE ai_usage_log (
    id                BIGSERIAL    NOT NULL,
    user_id           BIGINT       NOT NULL,
    key_id            BIGINT       NOT NULL,
    model             VARCHAR(100),
    prompt_tokens     INT,
    completion_tokens INT,
    source            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_usage_log PRIMARY KEY (id),
    CONSTRAINT fk_ai_usage_log_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_usage_log_key FOREIGN KEY (key_id) REFERENCES llm_api_keys(id) ON DELETE CASCADE
);
CREATE INDEX idx_ai_usage_log_user_created ON ai_usage_log (user_id, created_at);
CREATE INDEX idx_ai_usage_log_key ON ai_usage_log (key_id);
COMMENT ON TABLE ai_usage_log IS 'AI 调用 usage 计费日志(chat/summary/test 三类调用)';
COMMENT ON COLUMN ai_usage_log.source IS '调用来源:chat(用户对话) / summary(上下文摘要) / test(连通性测试)';
COMMENT ON COLUMN ai_usage_log.prompt_tokens IS '输入 token 数(OpenAI usage.prompt_tokens / Anthropic input_tokens)';
COMMENT ON COLUMN ai_usage_log.completion_tokens IS '输出 token 数(OpenAI usage.completion_tokens / Anthropic output_tokens)';
