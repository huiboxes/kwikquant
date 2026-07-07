-- Batch 6a: orders 加 reference_exchange(行情基准交易所,不可变,创建时定)。
-- 所有订单都写值:PAPER 账户 = account.referenceExchange(BINANCE/OKX/BITGET,行情基准所);
-- 真实交易所账户 = account.exchange(自身即行情源)。NOT NULL,legacy 行回填 BINANCE。
-- ExecutionService.applyFill 按 account.getExchange()(非此列)分流余额:PAPER 真扣/真实 noop。
ALTER TABLE orders ADD COLUMN reference_exchange VARCHAR(8) NOT NULL DEFAULT 'BINANCE';
