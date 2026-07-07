ALTER TABLE exchange_accounts
    ADD COLUMN reference_exchange VARCHAR(8);

-- legacy PAPER 账户回填 BINANCE(开发环境主基准);真实交易所账户 reference_exchange 留 NULL
UPDATE exchange_accounts
SET reference_exchange = 'BINANCE'
WHERE exchange = 'PAPER' AND reference_exchange IS NULL;

-- 修硬编码 bug:ExchangeAccountService.create 之前对所有账户 setPaperTrading(true)。
-- 默认 FALSE 后,真实交易所账户由应用层显式设 false(PAPER 由 create 设 true)。
ALTER TABLE exchange_accounts
    ALTER COLUMN paper_trading SET DEFAULT FALSE;
