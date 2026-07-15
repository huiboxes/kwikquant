-- TD-006: strategies 表加 version 列（策略版本号）
ALTER TABLE strategies ADD COLUMN version VARCHAR(20) NULL DEFAULT NULL;
COMMENT ON COLUMN strategies.version IS '策略版本号，如 v1.3.2';
