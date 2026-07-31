ALTER TABLE strategies ADD COLUMN stop_reason VARCHAR(500);

COMMENT ON COLUMN strategies.stop_reason IS '停止原因(ERROR 状态时存健康检查 reason;主动停/重启后清 null)';
