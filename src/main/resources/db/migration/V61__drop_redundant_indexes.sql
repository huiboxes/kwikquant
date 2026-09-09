-- 索引卫生:删除经审计确认无独占服务能力的冗余二级索引(全部 IF EXISTS,幂等)。
--
-- 1) idx_klines_lookup(V4_1):与 klines 主键 (exchange, market_type, symbol, interval, open_time)
--    同列同序、仅末列 DESC。PostgreSQL btree 支持反向扫描,本仓全部 klines 查询形态
--    (前 4 列等值 + open_time 排序/范围,见 KlineMapper findRecent/findBefore/findRange)
--    与 upsert 冲突仲裁(目标为主键列集)均由主键完整等价服务;该索引只给行情最高频
--    写表(WS 逐 candle upsert + 回测快照 batchUpsert)留下双份 btree 写放大。
--    同类清理先例:V3_1 drop idx_users_email。
--
-- 2-5) 四处"单列索引 ⊂ 同表复合索引左前缀"冗余(低频表,卫生清理):
--    idx_notif_pref_user(user_id)                ⊂ uk_notif_pref_user_evt_ch(user_id, event_type, channel_type)
--    idx_mcp_user(user_id)                       ⊂ uk_mcp_user_name(user_id, name)
--    idx_strategy_codes_strategy_id(strategy_id) ⊂ uk_strategy_codes_strategy_version(strategy_id, version_number)
--                                                  与 idx_strategy_codes_strategy_status(strategy_id, status)
--    idx_audit_logs_actor(actor_user_id)         ⊂ idx_audit_logs_actor_action_created(actor_user_id, action, created_at DESC)

DROP INDEX IF EXISTS idx_klines_lookup;
DROP INDEX IF EXISTS idx_notif_pref_user;
DROP INDEX IF EXISTS idx_mcp_user;
DROP INDEX IF EXISTS idx_strategy_codes_strategy_id;
DROP INDEX IF EXISTS idx_audit_logs_actor;
