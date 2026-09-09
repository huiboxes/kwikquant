-- V62: 资金费/报告域 schema 硬化 + positions legacy 桶身份回填 + audit_logs 冗余索引清理
--
-- 1) funding_settlements.bill_id NOT NULL:LIVE 幂等由 UNIQUE(account_id, bill_id) 管,
--    但 bill_id 可空 + PG 默认 NULLS DISTINCT → null 行互不撞键,schema 层对幂等零防护
--    (当前应用层堵死:LIVE adapter 对 null billId 行跳过不落账,PAPER 恒构造期次 billId;
--    手工补账/新 consumer 写入 null 即穿透)。存量 null 行回填 LEGACY-{id} 后收紧。
--    不用 UNIQUE NULLS NOT DISTINCT:会误杀双向持仓下同期两条合法 null position 行(V59 决策)。
-- 2) 枚举语义列补 CHECK 兜底(V32 先例纪律:防裸 SQL/迁移脚本/ORM bug 绕过应用层写非法值):
--    funding_rates.source(V57)、funding_settlements.rate_kind(V59)、
--    backtest_reports.market_type / trade_records.position_effect(V60)。
--    liquidation_model 刻意不加 CHECK:近似模型随引擎演进会扩枚举,加约束每次扩都要迁移。
-- 3) DROP idx_audit_logs_action / idx_audit_logs_created_at(V1):audit_logs 唯一读查询
--    (ActivityFeedService)走 WHERE actor_user_id = ? AND action IN (...) ORDER BY created_at DESC,
--    被 V30 复合索引 idx_audit_logs_actor_action_created(actor_user_id, action, created_at DESC)
--    完全覆盖;两个单列索引无任何独占服务的查询形态(V61 同类卫生清理,写放大白省)。

UPDATE funding_settlements SET bill_id = 'LEGACY-' || id WHERE bill_id IS NULL;

ALTER TABLE funding_settlements ALTER COLUMN bill_id SET NOT NULL;

ALTER TABLE funding_settlements
    ADD CONSTRAINT chk_funding_settlements_rate_kind CHECK (rate_kind IN ('SETTLED', 'PREDICTED'));

ALTER TABLE funding_rates
    ADD CONSTRAINT chk_funding_rates_source CHECK (source IN ('EXCHANGE', 'PROXY_BINANCE'));

ALTER TABLE backtest_reports
    ADD CONSTRAINT chk_backtest_reports_market_type CHECK (market_type IN ('SPOT', 'PERP'));

ALTER TABLE trade_records
    ADD CONSTRAINT chk_trade_records_position_effect
    CHECK (position_effect IS NULL OR position_effect IN ('OPEN_LONG', 'OPEN_SHORT', 'CLOSE_LONG', 'CLOSE_SHORT'));

-- 4) legacy PERP 行 position_side 回填:V31 时代净持仓模式存量行可能 position_side NULL 而
--    side 有值(long/short),该形态全链路平不掉(COALESCE 折叠成 'LONG',SHORT 意图查不中;
--    runner 派生 SHORT 后服务端 pre-trade gate 按 LONG 桶 qty=0 拒)。按 side 回填桶身份,
--    脏行(side 也非法)留 NULL 由消费方 warn+manual-review。
-- NOT EXISTS 守卫:同桶(account,symbol,marginMode,leverage)已存在 position_side=目标值 的行时
-- 跳过回填——V38 唯一索引按 COALESCE(position_side,'LONG') 折叠,legacy SHORT 行(NULL→'LONG' 键)
-- 与正规 SHORT 行今天可合法共存,无守卫的回填会让键折叠相撞 → 迁移硬失败堵死启动。
-- 冲突行留 NULL,由消费方 warn+manual-review 通道兜底(PaperExecutor/CrossLiquidationChecker 脏行守卫)。
UPDATE positions p
SET position_side = UPPER(p.side)
WHERE p.position_side IS NULL
  AND p.margin_mode IS NOT NULL
  AND UPPER(p.side) IN ('LONG', 'SHORT')
  AND NOT EXISTS (
      SELECT 1 FROM positions q
      WHERE q.account_id = p.account_id
        AND q.symbol = p.symbol
        AND q.position_side = UPPER(p.side)
        AND COALESCE(q.margin_mode, 'SPOT') = COALESCE(p.margin_mode, 'SPOT')
        AND COALESCE(q.leverage, 0) = COALESCE(p.leverage, 0)
        AND q.id <> p.id
  );

DROP INDEX IF EXISTS idx_audit_logs_action;
DROP INDEX IF EXISTS idx_audit_logs_created_at;
