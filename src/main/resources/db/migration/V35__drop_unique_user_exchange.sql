-- V35: drop UNIQUE(user_id, exchange) — 允许同用户同交易所多账户(模拟盘+实盘并存)
-- 量化平台同 OKX 既要测策略(模拟盘)又要跑实盘 runner(实盘),UNIQUE 阻止并存 = 屎山。
-- worker token 绑 accountId(strategy.exchange_account_id + WorkerTokenService.issueToken),
-- OrderController/PositionController 用 WORKER_ACCOUNT_ID_ATTR(不再 findByUserAndExchange 推导),
-- UNIQUE 的"推导无歧义"需求消除,可安全去掉。同 user 同 exchange 可多账户(用户自由,即使同 paperTrading/testnet 重复也允许)。

ALTER TABLE exchange_accounts DROP CONSTRAINT IF EXISTS uk_exchange_accounts_user_exchange;
