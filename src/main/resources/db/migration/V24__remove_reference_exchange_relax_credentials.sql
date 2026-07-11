-- 去掉 reference_exchange 这个字段：它和 exchange 表达的是同一件事("撮合/定价参考哪个真实
-- 交易所")，只是给模拟盘单独开了一条路，导致同一概念在 ExchangeAccount 上可空、在 Order 上
-- 又总是有值，读代码的人得记住这条隐规则。折回单一字段后，exchange 永远表示真实交易所，
-- 模拟与否交给 paper_trading 单独判断，前端"参考交易所"下拉框直接绑 exchange 即可。

-- exchange_accounts：把模拟盘账户的 reference_exchange 值搬回 exchange 本身，再删列。
UPDATE exchange_accounts
SET exchange = reference_exchange
WHERE exchange = 'PAPER' AND reference_exchange IS NOT NULL;

ALTER TABLE exchange_accounts DROP COLUMN reference_exchange;

-- orders：reference_exchange 和这里要的 exchange 语义完全一致（PAPER 账户 = 参考的真实交易所，
-- 实盘账户 = 自身），直接改名，不用搬数据。
ALTER TABLE orders RENAME COLUMN reference_exchange TO exchange;

-- 模拟盘账户不再强制填 apiKey/apiSecret（撮合走 exchange 字段指向的公开行情，不需要鉴权）。
ALTER TABLE exchange_accounts ALTER COLUMN api_key DROP NOT NULL;
ALTER TABLE exchange_accounts ALTER COLUMN api_secret DROP NOT NULL;
ALTER TABLE exchange_accounts ALTER COLUMN nonce DROP NOT NULL;
ALTER TABLE exchange_accounts ALTER COLUMN key_version DROP NOT NULL;

-- 历史模拟盘行之前存的是占位空 byte[]/占位 apiKey 字符串（不是真 NULL），折成真 NULL 更诚实——
-- 模拟盘本来就不该有"密文"这个概念，空 byte[] 会让人误以为它真的持有一份空的加密结果。
UPDATE exchange_accounts
SET api_secret = NULL, nonce = NULL, api_key = NULL
WHERE paper_trading = TRUE AND octet_length(api_secret) = 0;

-- 把"实盘必须有凭证""exchange 不能是 PAPER"这两条业务不变式钉在 DB 层，不止靠应用层校验。
ALTER TABLE exchange_accounts ADD CONSTRAINT chk_exchange_accounts_live_requires_key
    CHECK (paper_trading = TRUE OR (api_key IS NOT NULL AND api_secret IS NOT NULL));

ALTER TABLE exchange_accounts ADD CONSTRAINT chk_exchange_accounts_exchange_not_paper
    CHECK (exchange <> 'PAPER');
