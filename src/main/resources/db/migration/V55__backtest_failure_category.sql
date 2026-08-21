-- 回测失败分类列:FAILED 时由后端按 exit code + stderr 关键字归类,前端按分类展示用户可读文案。
-- nullable:历史 FAILED 记录无分类,前端兜底通用文案。
ALTER TABLE backtest_tasks ADD COLUMN failure_category VARCHAR(24);

COMMENT ON COLUMN backtest_tasks.failure_category IS
    '回测失败分类(FAILED 时有值): ENV_SETUP|MARKET_DATA|STRATEGY_CODE|QUOTA|TIMEOUT|INTERNAL';
