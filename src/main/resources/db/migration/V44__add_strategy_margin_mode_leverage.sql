-- 策略合约参数(marginMode/leverage),PERP 策略绑定保证金模式 + 杠杆。
-- SPOT 策略两字段均 null;PERP 策略 margin_mode='ISOLATED'/'CROSS', leverage 1-125。
-- 后端已实现 CROSS 全仓(marginRatio≥100% 全平),此处配合前端策略级绑定。
ALTER TABLE strategies ADD COLUMN margin_mode VARCHAR(10);
ALTER TABLE strategies ADD COLUMN leverage INTEGER;

COMMENT ON COLUMN strategies.margin_mode IS '合约保证金模式: PERP ISOLATED/CROSS; SPOT null';
COMMENT ON COLUMN strategies.leverage IS '合约杠杆倍数: PERP 1-125; SPOT null';
