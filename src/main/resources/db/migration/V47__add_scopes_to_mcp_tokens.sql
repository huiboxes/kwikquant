-- PAT 权限域:粗粒度 scope 逗号分隔存储(READ,BACKTEST,TRADE,LIVE,RISK,见 McpTokenScope)。
-- 存量令牌默认全权限(向后兼容,不破坏已接入的 agent);新签发默认 READ(最小权限,见 issue 端)。
ALTER TABLE mcp_tokens ADD COLUMN scopes VARCHAR(128) NOT NULL DEFAULT 'READ,BACKTEST,TRADE,LIVE,RISK';

COMMENT ON COLUMN mcp_tokens.scopes IS '权限域逗号分隔(READ/BACKTEST/TRADE/LIVE/RISK),新签发默认 READ';
