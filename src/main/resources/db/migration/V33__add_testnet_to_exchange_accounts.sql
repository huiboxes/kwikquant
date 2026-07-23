-- per-account testnet 标记(4b+):sandbox/testnet 信号从全局配置(CcxtProperties.sandbox)迁到 per-account,
-- 跟 paperTrading 同模式(用户配 key 时标 demo/testnet=true 或 prod=false)。
-- CcxtAuthExchangeFactory.setSandboxMode + OkxRestClient x-simulated-trading header 读本字段。
-- 现有账户 default false(生产 key);用户配 OKX demo key 时前端传 testnet=true。
ALTER TABLE exchange_accounts ADD COLUMN testnet BOOLEAN NOT NULL DEFAULT FALSE;
