-- fills (account_id, id) 复合索引:runner 断线增量补拉游标查询专用
-- (FillMapper.findCommittedSince / maxCommittedFillId:
--  WHERE account_id = ? AND id > ? [AND created_at < now()-2s] ORDER BY id LIMIT n)。
-- 无此索引时两条 SQL 的计划都有无界退化面:走 fills_pkey 范围扫要扫过游标之后的全部
-- **跨账户**行做 account_id 过滤(长驻 runner 游标陈旧时扫描量随停驻时长线性增长,
-- 60s 一次可撞 5s statement timeout → 补拉通道停摆);走 idx_fills_acct_time 则需回表
-- 该账户全部历史再按 id 排序。本索引把两条查询变成有界索引范围扫 + 早停。
-- fills 写入频率低(成交事件级),写放大可忽略;与 V61 索引卫生原则一致(只留有消费方的索引)。
CREATE INDEX idx_fills_acct_id ON fills (account_id, id);
