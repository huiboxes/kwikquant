CREATE TABLE mcp_tokens (
    id            BIGSERIAL       NOT NULL,
    user_id       BIGINT          NOT NULL,
    name          VARCHAR(64)     NOT NULL,
    token_hash    CHAR(64)        NOT NULL,
    salt          CHAR(32)        NOT NULL,
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_mcp_tokens PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_mcp_token_hash ON mcp_tokens (token_hash);
CREATE UNIQUE INDEX uk_mcp_user_name ON mcp_tokens (user_id, name);
CREATE INDEX idx_mcp_user ON mcp_tokens (user_id);

COMMENT ON TABLE mcp_tokens IS 'MCP Personal Access Token';
COMMENT ON COLUMN mcp_tokens.id IS '主键';
COMMENT ON COLUMN mcp_tokens.user_id IS '所属用户ID';
COMMENT ON COLUMN mcp_tokens.name IS 'token别名(用户自定义)';
COMMENT ON COLUMN mcp_tokens.token_hash IS 'HMAC-SHA-256(raw, pepper) hex (64字符, 查找哈希, 索引命中)';
COMMENT ON COLUMN mcp_tokens.salt IS 'per-token随机salt hex (16字节,32字符), 按 §5.2 schema 保留, 当前查找哈希使用 pepper-only (见 McpTokenHasher 说明)';
COMMENT ON COLUMN mcp_tokens.last_used_at IS '最后使用时间';
COMMENT ON COLUMN mcp_tokens.expires_at IS '过期时间(null=永不过期)';
COMMENT ON COLUMN mcp_tokens.revoked_at IS '吊销时间(null=有效)';
COMMENT ON COLUMN mcp_tokens.created_at IS '创建时间';
COMMENT ON COLUMN mcp_tokens.updated_at IS '更新时间(应用层维护)';
