-- V59: 资金费结算期次化 —— funding_settlements 加期次标识与期次幂等键;positions 加 opened_at
--
-- 背景:此前 PAPER 结算用本地墙钟当 settle_time,幂等键 billId 绑跑批时刻毫秒——重跑/多实例
-- 会双扣,宕机漏期无补偿,结算记录无法与交易所资金费期次网格对账。期次化把每条结算记录
-- 锚定到 funding_time(期次时刻),幂等键升级为 (account_id, position_id, funding_time)。
-- LIVE 行 position_id 可空(双向持仓模式同期两条 null 行合法),PG 默认 NULLS DISTINCT
-- 使 null 行互不撞新键,LIVE 幂等仍由 UNIQUE(account_id, bill_id) 管。

ALTER TABLE funding_settlements
    ADD COLUMN funding_time TIMESTAMPTZ,
    ADD COLUMN rate_kind VARCHAR(10),
    ADD COLUMN interval_seconds INT,
    ADD COLUMN mark_price NUMERIC(20, 8);

-- 存量回填(best-effort 归一化):funding_time 按 8h 网格截断(settle_time 以 UTC 解释;
-- 存量 PAPER 行墙钟语义本就模糊,截断只影响 catch-up watermark 初值,至多偏一期);
-- rate_kind 按 bill_id 前缀:PAPER- = 预估值结算(PREDICTED),其余 = LIVE 账单(SETTLED)
UPDATE funding_settlements
SET funding_time = to_timestamp(floor(extract(epoch from timezone('UTC', settle_time)) / 28800) * 28800),
    rate_kind    = CASE WHEN bill_id LIKE 'PAPER-%' THEN 'PREDICTED' ELSE 'SETTLED' END
WHERE funding_time IS NULL;

ALTER TABLE funding_settlements
    ALTER COLUMN funding_time SET NOT NULL,
    ALTER COLUMN rate_kind SET NOT NULL;

-- 防御性去重:同账户+持仓+期次多行只保留最早一条(null position_id 行 NULLS DISTINCT 不撞键,不参与)。
-- 被删行先整行归档:旧幂等键(墙钟毫秒 billId)重跑/多实例会双扣,双扣的钱在删除前已扣过余额,
-- DELETE 直接销毁对账证据(只剩 audit_logs 间接痕迹)——归档表保留核算依据,生产存量确有双扣时
-- 可按 archived 行差额补偿。DISTINCT:≥3 重复行时 join 会为同一 f 产出多份副本,归档去重
-- 防按行差额补偿时多算。IF NOT EXISTS 幂等;全新库(无重复行)归档为空表,零成本。
CREATE TABLE IF NOT EXISTS funding_settlements_dedup_archive AS
SELECT DISTINCT f.*
FROM funding_settlements f
JOIN funding_settlements keep
  ON f.account_id = keep.account_id
 AND f.position_id = keep.position_id
 AND f.funding_time = keep.funding_time
 AND f.id > keep.id
WHERE f.position_id IS NOT NULL;

COMMENT ON TABLE funding_settlements_dedup_archive IS
    'V59 去重 DELETE 的被删行归档(旧墙钟幂等键双扣的对账证据)。只读核算用:补偿差额以此为准,勿改写';

DELETE FROM funding_settlements f
USING funding_settlements keep
WHERE f.position_id IS NOT NULL
  AND f.account_id = keep.account_id
  AND f.position_id = keep.position_id
  AND f.funding_time = keep.funding_time
  AND f.id > keep.id;

ALTER TABLE funding_settlements
    ADD CONSTRAINT uq_funding_settlements_period UNIQUE (account_id, position_id, funding_time);

-- 持仓从 flat 打开的时刻:PAPER 资金费 catch-up 的结算下界(防新开仓被回收到开仓前的历史期次)。
-- 存量行不回填(null = 未知;调度器对 watermark 与 opened_at 双 null 的仓 fallback 只结最近一期)
ALTER TABLE positions ADD COLUMN opened_at TIMESTAMPTZ;

COMMENT ON COLUMN funding_settlements.funding_time IS '结算期次时刻(交易所资金费网格,如 OKX 0/8/16 UTC);PAPER 行 settle_time := funding_time';
COMMENT ON COLUMN funding_settlements.rate_kind IS '费率值类型:SETTLED=交易所已结算值 / PREDICTED=预估值(期次化前的历史过渡行)';
COMMENT ON COLUMN funding_settlements.interval_seconds IS '期次间隔秒(8h=28800 / 4h=14400 / 1h=3600);未知为 null';
COMMENT ON COLUMN funding_settlements.mark_price IS '结算所用标记价;未知为 null';
COMMENT ON COLUMN positions.opened_at IS '从 flat 打开仓的时刻(资金费 catch-up 下界);flat/存量行为 null';
