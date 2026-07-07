-- Batch 6a: orders 加 reference_exchange(行情基准交易所,不可变,创建时定)。
-- PAPER 账户 = BINANCE/OKX/BITGET(行情来源);真实交易所账户 = 自身 exchange(不写本列,留 null
-- 表示"行情源即 account.exchange",ExecutionService.applyFill 按 account.exchange 分流)。
-- legacy 行回填 BINANCE(开发环境主基准);新行由应用层 OrderMapper.insert 显式写值。
ALTER TABLE orders ADD COLUMN reference_exchange VARCHAR(8) NOT NULL DEFAULT 'BINANCE';
