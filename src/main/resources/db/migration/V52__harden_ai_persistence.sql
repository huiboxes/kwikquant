ALTER TABLE ai_usage_log
    DROP CONSTRAINT fk_ai_usage_log_key;

COMMENT ON COLUMN ai_usage_log.key_id IS
    '调用时使用的历史密钥ID；密钥删除后保留用于usage审计，不设外键';

ALTER TABLE ai_chat_messages
    ADD CONSTRAINT ck_ai_chat_messages_role
    CHECK (role IN ('user', 'assistant'));
