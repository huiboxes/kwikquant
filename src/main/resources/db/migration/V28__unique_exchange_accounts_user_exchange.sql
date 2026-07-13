-- FE-TD-038: 同交易所单账户不变量。
-- 保证 ExchangeAccountService.findByUserAndExchange(userId, exchange) 返回值唯一无歧义
-- （Worker 推导 account 路径依赖此不变量）。
-- 若存量存在重复 (user_id, exchange) 行, ADD CONSTRAINT 会失败 → 预检 RAISE 提示人工去重,
-- 不静默删（删 exchange_accounts 有 orders/positions 外键下游, 破坏性）。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (
            SELECT user_id, exchange
            FROM exchange_accounts
            GROUP BY user_id, exchange
            HAVING COUNT(*) > 1
        ) d
    ) THEN
        RAISE EXCEPTION 'Duplicate (user_id, exchange) rows in exchange_accounts; de-duplicate before adding UNIQUE constraint (FE-TD-038)';
    END IF;
END $$;

ALTER TABLE exchange_accounts
    ADD CONSTRAINT uk_exchange_accounts_user_exchange UNIQUE (user_id, exchange);

COMMENT ON CONSTRAINT uk_exchange_accounts_user_exchange ON exchange_accounts IS
    'FE-TD-038: 每用户每交易所最多一账户, 保证 findByUserAndExchange 无歧义推导（同交易所单账户产品规则）';
