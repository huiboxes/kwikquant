CREATE TABLE paper_balances (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT       NOT NULL,
    currency     VARCHAR(16)  NOT NULL,
    free         NUMERIC(20,8) NOT NULL DEFAULT 0,
    used         NUMERIC(20,8) NOT NULL DEFAULT 0,
    total        NUMERIC(20,8) NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_balances_acct_ccy UNIQUE (account_id, currency)
);

-- 不加 FK 引用 exchange_accounts:对齐 V5 orders / V7 positions 约定
-- (项目约定:exchange_accounts 引用 users,下游表不引用 exchange_accounts)。
-- UNIQUE (account_id, currency) 复合索引左前缀同时覆盖 findByAccount(account_id) 查询。
-- ON DELETE 级联由应用层保证(PaperBalanceAdapter.reset 主动删行)。
