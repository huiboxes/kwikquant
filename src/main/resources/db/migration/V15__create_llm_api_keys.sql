CREATE TABLE llm_api_keys (
    id              BIGSERIAL       NOT NULL,
    user_id         BIGINT          NOT NULL,
    label           VARCHAR(100)    NOT NULL,
    provider        VARCHAR(30)     NOT NULL,
    api_key         VARCHAR(8)      NOT NULL,
    api_secret      BYTEA           NOT NULL,
    nonce           BYTEA           NOT NULL,
    key_version     INTEGER         NOT NULL DEFAULT 1,
    base_url        VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_llm_api_keys PRIMARY KEY (id)
);

CREATE INDEX idx_llm_api_keys_user_id ON llm_api_keys (user_id);
CREATE UNIQUE INDEX uk_llm_api_keys_user_label ON llm_api_keys (user_id, label);

COMMENT ON TABLE llm_api_keys IS 'LLM API 密钥表';
COMMENT ON COLUMN llm_api_keys.id IS '密钥ID';
COMMENT ON COLUMN llm_api_keys.user_id IS '所属用户ID';
COMMENT ON COLUMN llm_api_keys.label IS '密钥标签（用户自定义）';
COMMENT ON COLUMN llm_api_keys.provider IS 'LLM 提供商: OPENAI/ANTHROPIC/OPENAI_COMPATIBLE';
COMMENT ON COLUMN llm_api_keys.api_key IS 'API Key 末尾 4 位明文（仅列表识别用，不含 secret 高熵部分）';
COMMENT ON COLUMN llm_api_keys.api_secret IS 'API Secret（AES-256-GCM 加密后的密文）';
COMMENT ON COLUMN llm_api_keys.nonce IS '加密 nonce（12 字节随机值）';
COMMENT ON COLUMN llm_api_keys.key_version IS '加密使用的 master key 版本';
COMMENT ON COLUMN llm_api_keys.base_url IS '自定义 API 端点 URL（OPENAI_COMPATIBLE 必填）';
COMMENT ON COLUMN llm_api_keys.created_at IS '创建时间';
COMMENT ON COLUMN llm_api_keys.updated_at IS '更新时间';
