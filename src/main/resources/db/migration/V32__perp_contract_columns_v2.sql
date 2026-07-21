-- V32: 合约(PERP)契约补全 —— 集中处理 V31 遗留(见 tech-design §2.1b/§13 拍板 1/2 + §11 m2-new + §12 M5-s/M6-s + §11 B1-new + §15 N1)
-- V31 已 commit main(margin_balance + mark_price 两僵尸列 + margin_mode VARCHAR(8) + 无 frozen_amount)。V32 集中 4 项 DDL。
-- MAX_INITIAL_MARGIN seed(§10 M8 + §12 m1-s)留阶段5a 实装时 INSERT(依赖 risk_policies 表结构 + RiskRuleType 枚举)。

-- ① frozen_amount 列(§13 拍板 1,per-position 累积 initialMargin,SPOT=0/PERP)
-- Position domain 加 frozenAmount 字段(§2.3),逐仓强平判 position.frozenAmount + 派生 unrealizedPnl(§12 B1-s 推翻 M12 不查 PaperBalance 共享桶)
ALTER TABLE positions ADD COLUMN frozen_amount NUMERIC(20,8) NOT NULL DEFAULT 0;
COMMENT ON COLUMN positions.frozen_amount IS 'per-position 累积 initialMargin(SPOT=0/PERP);§13 拍板 1,逐仓强平判此列 + 派生 unrealizedPnl';

-- ② margin_mode VARCHAR(8)→(16)(§11 m2-new + §12 M6-s 留余量,'ISOLATED'=8 刚好塞满,未来 'PORTFOLIO'/组合保证金 9+ 会截断)
ALTER TABLE positions ALTER COLUMN margin_mode TYPE VARCHAR(16);
ALTER TABLE orders ALTER COLUMN margin_mode TYPE VARCHAR(16);

-- ③ CHECK 约束(§12 M5-s 防非法 enum 值,MyBatis 反序列化 enum handler 之外加 DB 兜底,防裸 SQL/迁移脚本/ORM bug 写 'OPENN_LONG'/'OPEN Long')
ALTER TABLE positions ADD CONSTRAINT chk_positions_position_side CHECK (position_side IS NULL OR position_side IN ('LONG','SHORT'));
ALTER TABLE positions ADD CONSTRAINT chk_positions_margin_mode CHECK (margin_mode IS NULL OR margin_mode IN ('ISOLATED','CROSS'));
ALTER TABLE orders ADD CONSTRAINT chk_orders_position_effect CHECK (position_effect IS NULL OR position_effect IN ('OPEN_LONG','OPEN_SHORT','CLOSE_LONG','CLOSE_SHORT'));
ALTER TABLE orders ADD CONSTRAINT chk_orders_margin_mode CHECK (margin_mode IS NULL OR margin_mode IN ('ISOLATED','CROSS'));

-- ④ 僵尸列注释(V31 已 commit 回滚不了,只能注释;§11 B1-new margin_balance + §15 N1 mark_price,暂不读写全仓阶段才用)
-- §12 B1-s 推翻 M12:逐仓强平判 frozen_amount + 派生 unrealizedPnl,不读 margin_balance;§13 拍板 2 markPrice 内存 ConcurrentMap 不入 DB,不读 mark_price
COMMENT ON COLUMN positions.margin_balance IS '僵尸列暂不读写(§12 B1-s/M12 推翻,逐仓强平判 frozen_amount + 派生 unrealizedPnl);全仓阶段才用';
COMMENT ON COLUMN positions.mark_price IS '僵尸列暂不读写(§13 拍板 2 markPrice 内存 ConcurrentMap,PaperExecutor markPriceByPositionId);全仓阶段才用';
