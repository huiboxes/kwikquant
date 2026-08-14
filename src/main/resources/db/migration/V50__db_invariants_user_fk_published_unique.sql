-- DB 不变量加固(Wave 1.5):补用户级联 FK + strategy_codes 每策略至多一行 PUBLISHED。
-- 与 V48(role 数据修正)关注点分离:V48 数据语义,V50 引用完整性 + 唯一约束(均上线前 schema 加固)。
-- 软删 status 守卫在应用层 StrategyCrudService.requireDeletable(主防线) + StrategyMapper.softDelete SQL(深度防御),不在本迁移。

-- ① 三表补 user_id → users ON DELETE CASCADE(修用户注销孤儿密钥/令牌/回测任务)。
--    现状(V15 llm_api_keys / V18 mcp_tokens / V14 backtest_tasks)三表 user_id 仅 BIGINT NOT NULL +
--    普通索引,无 FK → 用户物理删不级联清,留孤儿密钥/令牌(泄漏面)/回测任务。补 CASCADE 兜底,
--    同时 FK 约束防止插入不存在的 user_id(引用完整性)。范式同 V39/V49(ai_chat_messages/ai_usage_log)。
ALTER TABLE llm_api_keys
    ADD CONSTRAINT fk_llm_api_keys_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE mcp_tokens
    ADD CONSTRAINT fk_mcp_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE backtest_tasks
    ADD CONSTRAINT fk_backtest_tasks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- ② strategy_codes 每策略至多一行 PUBLISHED(部分唯一索引)。
--    现状靠应用层 getPublishedCode 取唯一,DB 无强制;并发发布/脏数据可致同 strategy_id 多行
--    PUBLISHED(应用层取哪个不确定 → worker 跑旧版风险)。部分唯一索引在 DB 层强制至多一行。
CREATE UNIQUE INDEX uk_strategy_codes_published ON strategy_codes (strategy_id) WHERE status = 'PUBLISHED';
