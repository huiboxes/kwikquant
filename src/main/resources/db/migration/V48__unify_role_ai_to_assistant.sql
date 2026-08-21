-- 统一 AI 会话消息 role 语义:存量 role='ai' → 'assistant',对齐 LLM 协议(system/user/assistant)。
-- 消除后端写入不一致(saveAiMessage 存 "ai"、摘要合成消息存 "assistant")与前端 ai→assistant 重映射兜底。
-- 迁移后 role ∈ {user, assistant};AiChatController.saveAiMessage 改写 "assistant",前端 history 直用无需映射。
-- role 列 VARCHAR(10):'assistant' 9 字符可容纳;'user'/'system'(system 不落库) 均在内。
-- 幂等:WHERE role='ai' 重复执行无副作用(已迁移的 'ai' 不存在则 0 行)。
UPDATE ai_chat_messages SET role = 'assistant' WHERE role = 'ai';

COMMENT ON COLUMN ai_chat_messages.role IS
    '消息角色:user(用户输入) / assistant(AI 回复,含合成摘要消息);对齐 LLM 协议,前端直用';
